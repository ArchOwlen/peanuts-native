// PNRT shim for Eden's core/core_timing.h. The runtime schedules one looping "DSMod::Tick" event;
// here the event's callback is kept and the host fires it from pn_rt_tick (no timing thread).
#pragma once

#include <chrono>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "common/common_types.h"

namespace Core::Timing {

struct EventType {
    using Callback =
        std::function<std::optional<std::chrono::nanoseconds>(s64 user_data, std::chrono::nanoseconds late)>;
    std::string name;
    Callback callback;
};

inline std::shared_ptr<EventType> CreateEvent(std::string name, EventType::Callback callback) {
    return std::make_shared<EventType>(EventType{std::move(name), std::move(callback)});
}

class CoreTiming {
public:
    void ScheduleLoopingEvent(std::chrono::nanoseconds, std::chrono::nanoseconds,
                              const std::shared_ptr<EventType>& event) {
        std::scoped_lock lk{mutex};
        events.push_back(event);
    }
    void UnscheduleEvent(const std::shared_ptr<EventType>& event) {
        std::scoped_lock lk{mutex};
        std::erase(events, event);
    }
    /// Runs every scheduled looping event once (the host's tick).
    void FireAll() {
        std::vector<std::shared_ptr<EventType>> copy;
        {
            std::scoped_lock lk{mutex};
            copy = events;
        }
        for (const auto& e : copy) {
            if (e && e->callback) {
                e->callback(0, std::chrono::nanoseconds{0});
            }
        }
    }

private:
    std::mutex mutex;
    std::vector<std::shared_ptr<EventType>> events;
};

} // namespace Core::Timing
