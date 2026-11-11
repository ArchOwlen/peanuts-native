// PNRT shim for Eden's video_core/textures/workers.h + common/thread_worker.h: astc.cpp splits a
// decode into per-row-stride jobs on a thread pool. Here the jobs run inline on the calling
// thread (the DSMod asset worker is already off the tick thread).
#pragma once

#include <functional>

namespace Common {

class ThreadWorker {
public:
    void QueueWork(std::function<void()> work) {
        work();
    }
    void WaitForRequests() {}
};

} // namespace Common

namespace Tegra::Texture {

inline Common::ThreadWorker& GetThreadWorkers() {
    static Common::ThreadWorker workers;
    return workers;
}

} // namespace Tegra::Texture
