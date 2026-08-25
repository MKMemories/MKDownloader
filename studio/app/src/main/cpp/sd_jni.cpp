// Pont JNI entre MK Studio (Kotlin) et stable-diffusion.cpp.
// Un seul contexte modèle chargé à la fois ; la génération est bloquante et
// appelée depuis un thread dédié côté Kotlin.

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <string>

#include "stable-diffusion.h"

#define TAG "MKStudioNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jclass g_native_cls = nullptr;   // com.mkmemories.mkstudio.NativeSD (ref globale)
static jmethodID g_on_progress = nullptr;

static sd_ctx_t* g_ctx = nullptr;
static std::string g_loaded_path;

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
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mkmemories_mkstudio_NativeSD_loadModel(JNIEnv* env, jobject, jstring jpath, jint threads) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (g_ctx && g_loaded_path == path) {
        env->ReleaseStringUTFChars(jpath, path);
        return JNI_TRUE;   // déjà chargé
    }
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_loaded_path.clear();
    }
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path;
    p.n_threads = threads;
    // Optimisations CPU : flash attention (mémoire/vitesse) + convolution
    // directe pour le VAE (décodage final plus rapide).
    p.diffusion_flash_attn = true;
    p.vae_conv_direct = true;
    LOGI("Chargement du modèle: %s (threads=%d)", path, threads);
    g_ctx = new_sd_ctx(&p);
    if (g_ctx) g_loaded_path = path;
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
        g_loaded_path.clear();
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
