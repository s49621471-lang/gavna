#pragma once

namespace zyrex {

// Walks the whole IL2CPP domain and writes the dump set into out_dir.
// Runs on its own thread, already attached to the runtime. Never throws.
void run_dump(const char* out_dir);

} // namespace zyrex
