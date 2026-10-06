// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace flourite::camera {
// Bound allocations when reading proprietary static metadata. Counts are ints,
// not configurations: each configuration has format, width, height, direction.
constexpr size_t kMaxStreamInts = 16384;
enum class UpdateSite { Other, VendorStreams, LegacyDngStreams };

bool mergeRaw16(const int32_t* standard, size_t standardCount,
                const int32_t* extended, size_t extendedCount,
                std::vector<int32_t>& result);
int updateStaticStreams(void* metadata, uint32_t tag, const int32_t* data,
                        size_t count, UpdateSite site);
}  // namespace flourite::camera
