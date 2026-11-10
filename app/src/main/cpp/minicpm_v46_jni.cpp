#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {
constexpr const char * kTag = "EmreMiniCPM";
constexpr int32_t kContextSize = 4096;
constexpr int32_t kBatchSize = 1024;
constexpr int32_t kThreads = 4;

std::mutex g_mutex;
bool g_backend_initialized = false;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
mtmd_context * g_vision = nullptr;
std::string g_last_error;

void set_error(const std::string & message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

void release_model_locked() {
    if (g_vision) {
        mtmd_free(g_vision);
        g_vision = nullptr;
    }
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(1024);
    int32_t count = llama_token_to_piece(vocab, token, buffer.data(),
                                         static_cast<int32_t>(buffer.size()), 0, false);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocab, token, buffer.data(),
                                     static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (count <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(count));
}

bool decode_generated_token(llama_context * context, llama_batch & batch,
                            llama_token token, llama_pos position) {
    batch.n_tokens = 1;
    batch.token[0] = token;
    batch.pos[0] = position;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = 1;
    return llama_decode(context, batch) == 0;
}

jstring make_java_string(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_amresalehin_emregallery_ai_local_MiniCpmV46Native_initialize(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
        __android_log_print(ANDROID_LOG_INFO, kTag, "llama.cpp backend initialized");
    }
    g_last_error.clear();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_amresalehin_emregallery_ai_local_MiniCpmV46Native_loadModel(
        JNIEnv * env, jobject /*thiz*/, jstring j_model_path, jstring j_projector_path) {
    if (!j_model_path || !j_projector_path) return JNI_FALSE;
    const char * model_path = env->GetStringUTFChars(j_model_path, nullptr);
    const char * projector_path = env->GetStringUTFChars(j_projector_path, nullptr);
    if (!model_path || !projector_path) {
        if (model_path) env->ReleaseStringUTFChars(j_model_path, model_path);
        if (projector_path) env->ReleaseStringUTFChars(j_projector_path, projector_path);
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(j_model_path, model_path);
    if (!g_model) {
        env->ReleaseStringUTFChars(j_projector_path, projector_path);
        set_error("llama.cpp could not load the MiniCPM-V 4.6 language model GGUF.");
        return JNI_FALSE;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = kContextSize;
    context_params.n_batch = kBatchSize;
    context_params.n_ubatch = std::min<int32_t>(512, kBatchSize);
    context_params.n_threads = kThreads;
    context_params.n_threads_batch = kThreads;
    context_params.offload_kqv = false;
    g_context = llama_init_from_model(g_model, context_params);
    if (!g_context) {
        env->ReleaseStringUTFChars(j_projector_path, projector_path);
        release_model_locked();
        set_error("llama.cpp could not create the MiniCPM-V 4.6 inference context.");
        return JNI_FALSE;
    }

    mtmd_context_params vision_params = mtmd_context_params_default();
    vision_params.use_gpu = false;
    vision_params.print_timings = false;
    vision_params.n_threads = kThreads;
    vision_params.warmup = false;
    g_vision = mtmd_init_from_file(projector_path, g_model, vision_params);
    env->ReleaseStringUTFChars(j_projector_path, projector_path);

    if (!g_vision || !mtmd_support_vision(g_vision)) {
        release_model_locked();
        set_error("The MiniCPM-V 4.6 vision projector could not be initialized or does not expose vision input.");
        return JNI_FALSE;
    }

    g_last_error.clear();
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "MiniCPM-V 4.6 language model and F16 vision projector loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amresalehin_emregallery_ai_local_MiniCpmV46Native_generateImage(
        JNIEnv * env, jobject /*thiz*/, jbyteArray j_image, jstring j_prompt, jint max_tokens) {
    if (!j_image || !j_prompt) return nullptr;
    const jsize image_size = env->GetArrayLength(j_image);
    if (image_size <= 0) return nullptr;

    jbyte * image_data = env->GetByteArrayElements(j_image, nullptr);
    const char * prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    if (!image_data || !prompt_chars) {
        if (image_data) env->ReleaseByteArrayElements(j_image, image_data, JNI_ABORT);
        if (prompt_chars) env->ReleaseStringUTFChars(j_prompt, prompt_chars);
        return nullptr;
    }
    const std::string instruction(prompt_chars);
    env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_context || !g_vision) {
        env->ReleaseByteArrayElements(j_image, image_data, JNI_ABORT);
        set_error("MiniCPM-V 4.6 is not loaded.");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(g_context), true);

    // Follow OpenBMB's official Android flow: prefill the image marker and
    // vision tokens first, then evaluate the user text and assistant prefix.
    mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(
        g_vision,
        reinterpret_cast<const unsigned char *>(image_data),
        static_cast<size_t>(image_size)
    );
    env->ReleaseByteArrayElements(j_image, image_data, JNI_ABORT);
    if (!bitmap) {
        set_error("libmtmd could not decode the supplied image bytes.");
        return nullptr;
    }

    const char * marker = mtmd_default_marker();
    mtmd_input_text image_text = { marker, true, true };
    mtmd_input_chunks * image_chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[] = { bitmap };
    const int32_t image_tokenize_result =
        mtmd_tokenize(g_vision, image_chunks, &image_text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);
    if (image_tokenize_result != 0) {
        mtmd_input_chunks_free(image_chunks);
        set_error("libmtmd could not tokenize the MiniCPM-V image (code " +
                  std::to_string(image_tokenize_result) + ").");
        return nullptr;
    }

    llama_pos next_position = 0;
    const int32_t image_eval_result = mtmd_helper_eval_chunks(
        g_vision, g_context, image_chunks, 0, 0, kBatchSize, false, &next_position
    );
    mtmd_input_chunks_free(image_chunks);
    if (image_eval_result != 0) {
        set_error("libmtmd vision encoding failed (code " +
                  std::to_string(image_eval_result) + ").");
        return nullptr;
    }

    std::string prompt = "<|im_start|>user\\n" + instruction + "<|im_end|>\\n";
    prompt += "<|im_start|>assistant\\n<think>\\n\\n</think>\\n\\n";
    mtmd_input_text text_input = { prompt.c_str(), false, true };
    mtmd_input_chunks * text_chunks = mtmd_input_chunks_init();
    const int32_t text_tokenize_result =
        mtmd_tokenize(g_vision, text_chunks, &text_input, nullptr, 0);
    if (text_tokenize_result != 0) {
        mtmd_input_chunks_free(text_chunks);
        set_error("libmtmd could not tokenize the MiniCPM-V tagging prompt (code " +
                  std::to_string(text_tokenize_result) + ").");
        return nullptr;
    }

    const int32_t text_eval_result = mtmd_helper_eval_chunks(
        g_vision, g_context, text_chunks, next_position, 0, kBatchSize, true, &next_position
    );
    mtmd_input_chunks_free(text_chunks);
    if (text_eval_result != 0) {
        set_error("libmtmd prompt evaluation failed (code " +
                  std::to_string(text_eval_result) + ").");
        return nullptr;
    }

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (!sampler) {
        set_error("llama.cpp could not create the token sampler.");
        return nullptr;
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.15f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    llama_batch batch = llama_batch_init(1, 0, 1);
    if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
        llama_batch_free(batch);
        llama_sampler_free(sampler);
        set_error("llama.cpp could not allocate a generation batch.");
        return nullptr;
    }

    std::string generated;
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int token_limit = std::clamp<int>(max_tokens, 1, 128);
    for (int i = 0; i < token_limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        generated += token_piece(vocab, token);
        llama_sampler_accept(sampler, token);
        if (!decode_generated_token(g_context, batch, token, next_position++)) {
            set_error("llama.cpp failed while decoding a generated MiniCPM-V token.");
            generated.clear();
            break;
        }
    }

    llama_batch_free(batch);
    llama_sampler_free(sampler);

    if (generated.empty()) {
        if (g_last_error.empty()) set_error("MiniCPM-V 4.6 generated no text.");
        return nullptr;
    }
    g_last_error.clear();
    return make_java_string(env, generated);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amresalehin_emregallery_ai_local_MiniCpmV46Native_lastError(
        JNIEnv * env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return make_java_string(env, g_last_error);
}

extern "C" JNIEXPORT void JNICALL
Java_com_amresalehin_emregallery_ai_local_MiniCpmV46Native_close(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
    g_last_error.clear();
}
