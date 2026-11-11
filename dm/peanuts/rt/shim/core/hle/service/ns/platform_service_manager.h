// PNRT shim for Eden's core/hle/service/ns/platform_service_manager.h: the shared-font table and
// the .bfttf -> TTF decrypt (mod_sysfont_source.cpp, the "sysfont:" source). SHARED_FONTS is
// Eden's own table; DecryptSharedFontToTTF is reproduced in shim/shim.cpp (XOR with the key
// derived from the first word, then byte-swap, header dropped).
#pragma once

#include <array>
#include <utility>
#include <vector>

#include "common/common_types.h"

namespace Service::NS {

enum class FontArchives : u64 {
    Extension = 0x0100000000000810,
    Standard = 0x0100000000000811,
    Korean = 0x0100000000000812,
    ChineseTraditional = 0x0100000000000813,
    ChineseSimple = 0x0100000000000814,
};

constexpr std::array<std::pair<FontArchives, const char*>, 7> SHARED_FONTS{
    std::make_pair(FontArchives::Standard, "nintendo_udsg-r_std_003.bfttf"),
    std::make_pair(FontArchives::ChineseSimple, "nintendo_udsg-r_org_zh-cn_003.bfttf"),
    std::make_pair(FontArchives::ChineseSimple, "nintendo_udsg-r_ext_zh-cn_003.bfttf"),
    std::make_pair(FontArchives::ChineseTraditional, "nintendo_udjxh-db_zh-tw_003.bfttf"),
    std::make_pair(FontArchives::Korean, "nintendo_udsg-r_ko_003.bfttf"),
    std::make_pair(FontArchives::Extension, "nintendo_ext_003.bfttf"),
    std::make_pair(FontArchives::Extension, "nintendo_ext2_003.bfttf"),
};

void DecryptSharedFontToTTF(const std::vector<u32>& input, std::vector<u8>& output);

} // namespace Service::NS
