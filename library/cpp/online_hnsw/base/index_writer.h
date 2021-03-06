#pragma once

#include "index_data.h"

#include <util/stream/output.h>
#include <util/generic/string.h>


namespace NOnlineHnsw {
    void WriteIndex(const TOnlineHnswIndexData& index, IOutputStream& out);

    void WriteIndex(const TOnlineHnswIndexData& index, const TString& fileName);
} // namespace NOnlineHnsw
