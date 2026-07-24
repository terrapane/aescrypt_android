/*
 *  process_control.h
 *
 *  Copyright (C) 2026
 *  Terrapane Corporation
 *  All Rights Reserved
 *
 *  Author:
 *      Paul E. Jones <paulej@packetizer.com>
 *
 *  Description:
 *      This header defines a simple ProcessControl structure used control
 *      thread execution and percentage complete.
 *
 *  Portability Issues:
 *      None.
 */

#pragma once

#include <condition_variable>
#include <mutex>
#include <atomic>
#include <concepts>

namespace Terra
{

// Simple class to facilitate process control
class ProcessControl
{
    public:
        // Reset the process control object (not cancelled and 0% complete)
        void Reset()
        {
            std::lock_guard<std::mutex> lock(mutex);
            percent_complete = 0.0f;
            cancel = false;
        }

        // Set the cancellation flag
        void SetCancel()
        {
            std::lock_guard<std::mutex> lock(mutex);
            cancel = true;
            cv.notify_all();
        }

        // Check to see if the cancel flag is set
        bool IsCancelSet(bool caller_locked = false)
        {
            // Does the caller claim to have the mutex locked?
            if (caller_locked) return cancel;

            std::lock_guard<std::mutex> lock(mutex);
            return cancel;
        }

        // Get the percent complete
        float GetPercentComplete()
        {
            std::lock_guard<std::mutex> lock(mutex);
            return percent_complete;
        }

        // Set the percent complete
        void SetPercentComplete(float percentage)
        {
            std::lock_guard<std::mutex> lock(mutex);
            percent_complete = percentage;
        }

        // Used for thread synchronization
        std::condition_variable cv;
        std::mutex mutex;

    private:
        bool cancel{false};
        float percent_complete = 0.0f;
};

} // namespace Terra
