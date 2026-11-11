// PNRT shim for Eden's common/fs/path_util.h: GetEdenPath(EdenPath). Eden resolves these to its
// user-data directory; here the host names one writable data directory (PnRtConfig::data_dir,
// SetEdenDir) and EdenDir = <data_dir>, CacheDir = <data_dir>/cache. Used by the persisted
// flags (<EdenDir>/dualscreen/persist), the "user:" source (<EdenDir>/dualscreen/user) and the
// module staging directory (<CacheDir>/dualscreen, unless SetModuleCacheDirectory).
#pragma once

#include <filesystem>

namespace Common::FS {

enum class EdenPath {
    EdenDir,
    CacheDir,
    ConfigDir,
    DumpDir,
    LoadDir,
    LogDir,
    NANDDir,
    SDMCDir,
    ScreenshotsDir,
    ShaderDir,
    TASDir,
    IconsDir,
};

/// PNRT: the host's data directory (default: <temp>/peanuts_rt until set).
void SetEdenDir(const std::filesystem::path& dir);
[[nodiscard]] std::filesystem::path GetEdenPath(EdenPath path);

} // namespace Common::FS
