// Pont JNI entre MK Studio (Kotlin) et stable-diffusion.cpp.
// Un seul contexte modèle chargé à la fois ; la génération est bloquante et
// appelée depuis un thread dédié côté Kotlin.

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>

#include "stable-diffusion.h"

#define TAG "MKStudioNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jclass g_native_cls = nullptr;   // com.mkmemories.mkstudio.NativeSD (ref globale)
static jmethodID g_on_progress = nullptr;

static sd_ctx_t* g_ctx = nullptr;
static std::string g_loaded_key;   // model_path|taesd_path

// Journal natif : tampon circulaire des messages du moteur (INFO et plus),
// récupéré côté Kotlin pour le « Journal technique » de l'app.
static std::mutex g_log_mutex;
static std::deque<std::string> g_log_lines;

static void push_log(const char* level, const char* text) {
    std::string line = std::string("[") + level + "] " + (text ? text : "");
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_lines.push_back(std::move(line));
    while (g_log_lines.size() > 400) g_log_lines.pop_front();
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("com/mkmemories/mkstudio/NativeSD");
    if (cls) {
        g_native_cls = static_cast<jclass>(env->NewGlobalRef(cls));
        g_on_progress = env->GetStaticMethodID(g_native_cls, "onProgress", "(II)V");
    }
    return JNI_VERSION_1_6;
}

// La génération étant bloquante, le callback de progression arrive sur le même
// thread que l'appel JNI : on peut réutiliser son JNIEnv.
static thread_local JNIEnv* t_env = nullptr;

static void progress_cb(int step, int steps, float /*time*/, void*) {
    if (t_env && g_native_cls && g_on_progress) {
        t_env->CallStaticVoidMethod(g_native_cls, g_on_progress, step, steps);
        if (t_env->ExceptionCheck()) t_env->ExceptionClear();
    }
}

static void log_cb(enum sd_log_level_t level, const char* text, void*) {
    if (level >= SD_LOG_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    if (level >= SD_LOG_INFO) {
        push_log(level == SD_LOG_INFO ? "info" : (level == SD_LOG_WARN ? "warn" : "erreur"), text);
    }
}

// Récupère et vide le journal natif (une ligne par entrée).
extern "C" JNIEXPORT jstring JNICALL
Java_com_mkmemories_mkstudio_NativeSD_getLogs(JNIEnv* env, jobject) {
    std::string joined;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        for (auto& l : g_log_lines) { joined += l; joined += '\n'; }
        g_log_lines.clear();
    }
    return env->NewStringUTF(joined.c_str());
}

// Capacités CPU détectées par ggml (NEON, DOTPROD, FP16…) — pour le journal.
extern "C" JNIEXPORT jstring JNICALL
Java_com_mkmemories_mkstudio_NativeSD_systemInfo(JNIEnv* env, jobject) {
    const char* info = sd_get_system_info();
    return env->NewStringUTF(info ? info : "?");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mkmemories_mkstudio_NativeSD_loadModel(JNIEnv* env, jobject, jstring jpath, jint threads,
                                                jstring jtaesd) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    std::string taesd;
    if (jtaesd) {
        const char* t = env->GetStringUTFChars(jtaesd, nullptr);
        taesd = t;
        env->ReleaseStringUTFChars(jtaesd, t);
    }
    std::string key = std::string(path) + "|" + taesd;
    if (g_ctx && g_loaded_key == key) {
        env->ReleaseStringUTFChars(jpath, path);
        return JNI_TRUE;   // déjà chargé
    }
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_loaded_key.clear();
    }
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path;
    p.n_threads = threads;
    // TAESD : mini-décodeur (~10 Mo) qui remplace le VAE pour l'image finale
    // → décodage en ~1 s au lieu de 10-30 s (légère perte de finesse).
    if (!taesd.empty()) p.taesd_path = taesd.c_str();
    // Optimisations CPU : flash attention + convolution directe (diffusion).
    // PAS de conv directe côté VAE : mesuré 10,9 s de décodage TAESD avec,
    // le mini-décodeur préfère le chemin classique.
    p.diffusion_flash_attn = true;
    p.vae_conv_direct = false;
    p.diffusion_conv_direct = true;
    // LoRA fusionné UNE FOIS dans les poids (au 1er lancement) au lieu d'être
    // appliqué à la volée À CHAQUE étape — mesuré comme gros frein (journal
    // S23 Ultra : « apply lora at runtime », ~38 s/étape).
    p.lora_apply_mode = LORA_APPLY_IMMEDIATELY;
    push_log("app", ("chargement du modèle: " + std::string(path) +
                     " threads=" + std::to_string(threads) +
                     (taesd.empty() ? "" : " taesd=oui")).c_str());
    LOGI("Chargement du modèle: %s (threads=%d)", path, threads);
    g_ctx = new_sd_ctx(&p);
    if (g_ctx) g_loaded_key = key;
    env->ReleaseStringUTFChars(jpath, path);
    LOGI("Chargement: %s", g_ctx ? "OK" : "ECHEC");
    return g_ctx ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mkmemories_mkstudio_NativeSD_isLoaded(JNIEnv*, jobject) {
    return g_ctx ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mkmemories_mkstudio_NativeSD_unload(JNIEnv*, jobject) {
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_loaded_key.clear();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mkmemories_mkstudio_NativeSD_cancel(JNIEnv*, jobject) {
    if (g_ctx) sd_cancel_generation(g_ctx, SD_CANCEL_ALL);
}

// Génère une image. init == null → texte seul ; sinon retouche (img2img) :
// init est un tableau RGB (w*h*3) DÉJÀ redimensionné aux dimensions demandées.
// jlora facultatif (accélérateur LCM…) ; lcm = échantillonneur LCM.
// Renvoie un tableau RGB (width*height*3) ou null en cas d'échec/annulation.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mkmemories_mkstudio_NativeSD_generate(JNIEnv* env, jobject,
                                               jstring jprompt, jstring jnegative,
                                               jint width, jint height, jint steps,
                                               jfloat cfg, jlong seed,
                                               jbyteArray jinit, jfloat strength,
                                               jstring jlora, jfloat lora_mult,
                                               jboolean lcm) {
    if (!g_ctx) return nullptr;
    t_env = env;

    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    const char* negative = env->GetStringUTFChars(jnegative, nullptr);
    std::string lora_path;
    if (jlora) {
        const char* lp = env->GetStringUTFChars(jlora, nullptr);
        lora_path = lp;
        env->ReleaseStringUTFChars(jlora, lp);
    }

    sd_img_gen_params_t g;
    sd_img_gen_params_init(&g);
    sd_sample_params_init(&g.sample_params);
    g.prompt = prompt;
    g.negative_prompt = negative;
    g.width = width;
    g.height = height;
    g.seed = seed;
    g.batch_count = 1;
    g.sample_params.sample_steps = steps;
    g.sample_params.guidance.txt_cfg = cfg;

    sd_lora_t lora{};
    if (!lora_path.empty()) {
        lora.is_high_noise = false;
        lora.multiplier = lora_mult;
        lora.path = lora_path.c_str();
        g.loras = &lora;
        g.lora_count = 1;
    }
    if (lcm) {
        g.sample_params.sample_method = LCM_SAMPLE_METHOD;
    }

    jbyte* init_bytes = nullptr;
    if (jinit) {
        init_bytes = env->GetByteArrayElements(jinit, nullptr);
        g.init_image.width = width;
        g.init_image.height = height;
        g.init_image.channel = 3;
        g.init_image.data = reinterpret_cast<uint8_t*>(init_bytes);
        g.strength = strength;
    }

    sd_image_t* images = nullptr;
    int num = 0;
    bool ok = generate_image(g_ctx, &g, &images, &num);

    if (init_bytes) env->ReleaseByteArrayElements(jinit, init_bytes, JNI_ABORT);
    env->ReleaseStringUTFChars(jprompt, prompt);
    env->ReleaseStringUTFChars(jnegative, negative);

    jbyteArray out = nullptr;
    if (ok && images && num > 0 && images[0].data) {
        const sd_image_t& im = images[0];
        const size_t len = static_cast<size_t>(im.width) * im.height * im.channel;
        if (im.channel == 3) {
            out = env->NewByteArray(static_cast<jsize>(len));
            if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(len),
                                             reinterpret_cast<const jbyte*>(im.data));
        }
    }
    if (images) free_sd_images(images, num);
    t_env = nullptr;
    return out;
}
