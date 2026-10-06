// SPDX-License-Identifier: Apache-2.0
#include <ui/GraphicBuffer.h>

// Keep the pinned camera allocation fix in sync with the vendor libui headers.
// A future platform ABI change must fail the build, not corrupt the camera heap.
static_assert(sizeof(android::GraphicBuffer) == 0xd30,
              "Re-audit flourite camera GraphicBuffer allocation fixups");
