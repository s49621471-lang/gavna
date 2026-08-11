#pragma once

namespace zyrex {

// Samples the local player's field values over time and writes them to out_dir.
//
// Deliberately does not call a single Unity API. Everything here is a plain
// memory read off the object the runtime already handed us, so there is no
// main-thread requirement and no new crash surface beyond a bad pointer, which
// the fault guard already covers.
//
// duration_sec  total wall time to sample for
// interval_ms   gap between samples
void run_probe(const char* out_dir, int duration_sec, int interval_ms);

} // namespace zyrex
