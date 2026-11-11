// PNRT shim for Eden's video_core/gpu.h: the lifted code only asks the GPU for DSModAux().
#pragma once

#include "video_core/dsmod/aux_routing.h"

namespace Tegra {

class GPU {
public:
    [[nodiscard]] VideoCore::DSMod::AuxRouting& DSModAux() {
        return aux;
    }
    VideoCore::DSMod::AuxRouting aux;
};

} // namespace Tegra
