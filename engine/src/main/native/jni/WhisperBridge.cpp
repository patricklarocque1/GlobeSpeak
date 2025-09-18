#include <jni.h>
#include <android/log.h>

#include "whisper.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdarg>
#include <deque>
#include <iterator>
#include <memory>
#include <new>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char *kLogTag = "WhisperBridge";
constexpr int kWhisperSampleRate = 16000;
constexpr int kStepMs = 500;      // poll partials roughly every 500 ms
constexpr int kWindowMs = 5000;    // analyze the trailing 5 s for partials
constexpr int kMaxWindowMs = 30000; // keep up to 30 s of context

inline void logError(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, kLogTag, fmt, args);
    va_end(args);
}

inline void logInfo(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, kLogTag, fmt, args);
    va_end(args);
}

inline void logWarn(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_WARN, kLogTag, fmt, args);
    va_end(args);
}

std::string trimCopy(const std::string &src) {
    auto begin = src.find_first_not_of(" \t\n\r");
    if (begin == std::string::npos) {
        return "";
    }
    auto end = src.find_last_not_of(" \t\n\r");
    return src.substr(begin, end - begin + 1U);
}

struct NativeWhisper {
    explicit NativeWhisper(struct whisper_context *ctx_ptr)
        : ctx(ctx_ptr) {
        params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_progress = false;
        params.print_realtime = false;
        params.print_special = false;
        params.translate = false;
        params.no_context = true;
        params.no_timestamps = true;
        params.single_segment = true;
        params.max_tokens = 0;
        params.temperature = 0.0f;
        params.temperature_inc = 0.2f;
        params.detect_language = true;
        params.language = nullptr;

        int hw_threads = static_cast<int>(std::thread::hardware_concurrency());
        if (hw_threads <= 0) {
            hw_threads = 2;
        }
        params.n_threads = std::max(2, hw_threads);

        resetSizing(kWhisperSampleRate);
    }

    ~NativeWhisper() {
        stopWorker();
        if (ctx) {
            whisper_free(ctx);
            ctx = nullptr;
        }
    }

    bool start(int sample_rate) {
        std::lock_guard<std::mutex> lg(mutex);
        if (!ctx) {
            logError("start called with null whisper context");
            return false;
        }
        if (running) {
            return true;
        }
        resetSizing(sample_rate);
        running = true;
        stop_requested = false;
        finalize_requested = false;
        partial_available = false;
        final_available = false;
        overflow_notified = false;
        partial_text.clear();
        final_text.clear();
        partial_seq = 0;
        total_ingested = 0;
        last_decode_cursor = 0;
        pcm.clear();

        worker = std::thread(&NativeWhisper::loop, this);
        return true;
    }

    void acceptPcm(const int16_t *samples, jint length) {
        if (!samples || length <= 0) {
            return;
        }
        std::unique_lock<std::mutex> lock(mutex);
        if (!running) {
            return;
        }
        for (jint i = 0; i < length; ++i) {
            const float normalized = static_cast<float>(samples[i]) / 32768.0f;
            pcm.push_back(normalized);
            if (pcm.size() > max_samples) {
                pcm.pop_front();
                if (!overflow_notified) {
                    overflow_notified = true;
                    logWarn("Audio buffer overflow; dropping oldest samples to keep up");
                }
            }
        }
        total_ingested += static_cast<int64_t>(length);
        lock.unlock();
        cv.notify_all();
    }

    std::optional<std::string> pollPartial() {
        std::lock_guard<std::mutex> lg(mutex);
        if (!partial_available) {
            return std::nullopt;
        }
        partial_available = false;
        return partial_text;
    }

    std::optional<std::string> pollFinal() {
        std::lock_guard<std::mutex> lg(mutex);
        if (!final_available) {
            return std::nullopt;
        }
        final_available = false;
        return final_text;
    }

    void requestFinalize() {
        {
            std::lock_guard<std::mutex> lg(mutex);
            if (!running || finalize_requested) {
                return;
            }
            finalize_requested = true;
        }
        cv.notify_all();
        stopWorker();
    }

    void stopImmediate() {
        {
            std::lock_guard<std::mutex> lg(mutex);
            stop_requested = true;
        }
        cv.notify_all();
        stopWorker();
    }

private:
    struct whisper_context *ctx = nullptr;
    struct whisper_full_params params;

    std::deque<float> pcm;
    size_t min_samples_for_decode = 0;
    size_t window_samples = 0;
    size_t max_samples = 0;
    int sample_rate = kWhisperSampleRate;

    std::mutex mutex;
    std::condition_variable cv;
    std::thread worker;

    bool running = false;
    bool stop_requested = false;
    bool finalize_requested = false;
    bool partial_available = false;
    bool final_available = false;
    bool overflow_notified = false;

    std::string partial_text;
    std::string final_text;
    int partial_seq = 0;
    int64_t total_ingested = 0;
    int64_t last_decode_cursor = 0;

    void resetSizing(int rate) {
        sample_rate = (rate > 0) ? rate : kWhisperSampleRate;
        if (sample_rate != kWhisperSampleRate) {
            logInfo("Resampling not implemented; expected %d Hz, received %d Hz", kWhisperSampleRate, sample_rate);
        }
        const size_t kStepSamples = static_cast<size_t>(static_cast<int64_t>(sample_rate) * kStepMs / 1000);
        const size_t kWindowSamples = static_cast<size_t>(static_cast<int64_t>(sample_rate) * kWindowMs / 1000);
        const size_t kMaxSamples = static_cast<size_t>(static_cast<int64_t>(sample_rate) * kMaxWindowMs / 1000);

        min_samples_for_decode = std::max<size_t>(kStepSamples, sample_rate / 4);
        window_samples = std::max<size_t>(kStepSamples, kWindowSamples);
        max_samples = std::max(window_samples, kMaxSamples);
        overflow_notified = false;
    }

    void stopWorker() {
        if (worker.joinable()) {
            worker.join();
        }
    }

    void loop() {
        std::unique_lock<std::mutex> lock(mutex);
        while (true) {
            cv.wait(lock, [&] {
                return stop_requested || finalize_requested || shouldDecodeLocked();
            });

            if (stop_requested && !finalize_requested) {
                break;
            }

            const bool do_finalize = finalize_requested;
            if (!do_finalize && !shouldDecodeLocked()) {
                continue;
            }

            std::vector<float> window = buildWindowLocked();
            last_decode_cursor = total_ingested;

            lock.unlock();
            auto transcript = infer(window);
            lock.lock();

            const std::string trimmed = trimCopy(transcript);
            if (trimmed != partial_text) {
                partial_text = trimmed;
                partial_available = true;
                ++partial_seq;
            }

            if (do_finalize) {
                final_text = trimmed;
                final_available = true;
                finalize_requested = false;
                stop_requested = true;
                running = false;
                break;
            }
        }
        running = false;
    }

    bool shouldDecodeLocked() const {
        if (!running) {
            return false;
        }
        if (pcm.size() < min_samples_for_decode) {
            return false;
        }
        const int64_t delta = total_ingested - last_decode_cursor;
        const int64_t min_step = static_cast<int64_t>(sample_rate) * kStepMs / 1000;
        return delta >= min_step;
    }

    std::vector<float> buildWindowLocked() {
        const size_t available = pcm.size();
        const size_t start_index = (available > window_samples) ? (available - window_samples) : 0;
        std::vector<float> window;
        window.reserve(available - start_index);
        auto it = pcm.begin();
        std::advance(it, static_cast<long>(start_index));
        for (; it != pcm.end(); ++it) {
            window.push_back(*it);
        }
        return window;
    }

    std::string infer(const std::vector<float> &audio) {
        if (audio.empty()) {
            return "";
        }
        auto local_params = params;
        const int n_samples = static_cast<int>(audio.size());
        const int status = whisper_full(ctx, local_params, audio.data(), n_samples);
        if (status != 0) {
            logError("whisper_full returned %d", status);
            return "";
        }

        const int n_segments = whisper_full_n_segments(ctx);
        std::string result;
        result.reserve(128);
        for (int i = 0; i < n_segments; ++i) {
            const char *segment = whisper_full_get_segment_text(ctx, i);
            if (segment) {
                result.append(segment);
            }
        }
        return result;
    }
};

NativeWhisper *fromHandle(jlong handle) {
    return reinterpret_cast<NativeWhisper *>(handle);
}

std::string JStringToUtf8(JNIEnv *env, jstring value) {
    if (!value) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        return {};
    }
    std::string copy(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}

jstring toJString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_globespeak_engine_asr_nativebridge_WhisperBridge_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const std::string path = JStringToUtf8(env, modelPath);
    if (path.empty()) {
        logError("Model path empty in nativeInit");
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (!ctx) {
        logError("Failed to initialize whisper context at %s", path.c_str());
        return 0;
    }

    auto *state = new (std::nothrow) NativeWhisper(ctx);
    if (!state) {
        logError("Failed to allocate NativeWhisper");
        whisper_free(ctx);
        return 0;
    }

    logInfo("Whisper context initialized: %s", path.c_str());
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_globespeak_engine_asr_nativebridge_WhisperBridge_nativeStart(
        JNIEnv *, jobject /*thiz*/, jlong handle, jint sampleRate) {
    auto *state = fromHandle(handle);
    if (!state) {
        logError("nativeStart called with null handle");
        return JNI_FALSE;
    }
    return state->start(sampleRate) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_globespeak_engine_asr_nativebridge_WhisperBridge_nativeAcceptPcm(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jshortArray pcm, jint length) {
    auto *state = fromHandle(handle);
    if (!state || !pcm) {
        return;
    }
    jshort *raw = env->GetShortArrayElements(pcm, nullptr);
    if (!raw) {
        return;
    }
    state->acceptPcm(reinterpret_cast<const int16_t *>(raw), length);
    env->ReleaseShortArrayElements(pcm, raw, JNI_ABORT);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_globespeak_engine_asr_nativebridge_WhisperBridge_nativePollPartial(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *state = fromHandle(handle);
    if (!state) {
        return nullptr;
    }
    auto partial = state->pollPartial();
    if (!partial.has_value()) {
        return nullptr;
    }
    return toJString(env, partial.value());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_globespeak_engine_asr_nativebridge_WhisperBridge_nativePollFinal(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *state = fromHandle(handle);
    if (!state) {
        return nullptr;
    }
    auto final = state->pollFinal();
    if (!final.has_value()) {
        return nullptr;
    }
    return toJString(env, final.value());
}

extern "C" JNIEXPORT void JNICALL
Java_com_globespeak_engine_asr_nativebridge_WhisperBridge_nativeStop(
        JNIEnv *, jobject /*thiz*/, jlong handle) {
    auto *state = fromHandle(handle);
    if (!state) {
        return;
    }
    state->requestFinalize();
}

extern "C" JNIEXPORT void JNICALL
Java_com_globespeak_engine_asr_nativebridge_WhisperBridge_nativeRelease(
        JNIEnv *, jobject /*thiz*/, jlong handle) {
    auto *state = fromHandle(handle);
    if (!state) {
        return;
    }
    state->stopImmediate();
    delete state;
}
