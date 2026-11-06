#pragma once

#include <ass/ass.h>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

namespace ass_atlas {

struct Placement {
    const ASS_Image *image;
    int x;
    int y;
};

struct Page {
    size_t first;
    size_t count;
    int height;
};

struct Layout {
    int width = 0;
    std::vector<Placement> images;
    std::vector<Page> pages;
};

inline int PowerOfTwo(int value) {
    int result = 1;
    while (result < value) result *= 2;
    return result;
}

// Pages contain contiguous runs of the input list, so batching cannot reorder blending.
// A replicated one-pixel border reproduces GL_CLAMP_TO_EDGE with linear sampling.
inline bool Plan(const ASS_Image *images, int max_size, size_t budget, Layout &layout) {
    layout.images.clear();
    layout.pages.clear();
    if (max_size < 4) return false;
    size_t area = 0;
    int widest = 0;
    for (auto *image = images; image != nullptr; image = image->next) {
        if (image->w <= 0 || image->h <= 0 || image->bitmap == nullptr || image->stride < image->w ||
            (image->color & 255) == 255) continue;
        if (image->w > max_size - 2 || image->h > max_size - 2) return false;
        area += static_cast<size_t>(image->w + 2) * (image->h + 2);
        widest = std::max(widest, image->w + 2);
    }
    if (area == 0) return true;
    layout.width = std::min(max_size, PowerOfTwo(std::max(widest, static_cast<int>(std::sqrt(area)))));
    int x = 0;
    int y = 0;
    int row_height = 0;
    layout.pages.push_back({0, 0, 0});
    for (auto *image = images; image != nullptr; image = image->next) {
        if (image->w <= 0 || image->h <= 0 || image->bitmap == nullptr || image->stride < image->w ||
            (image->color & 255) == 255) continue;
        const int width = image->w + 2;
        const int height = image->h + 2;
        if (x + width > layout.width) {
            y += row_height;
            x = 0;
            row_height = 0;
        }
        if (y + height > max_size) {
            if (layout.pages.size() == 4) return false;
            layout.pages.push_back({layout.images.size(), 0, 0});
            x = 0;
            y = 0;
            row_height = 0;
        }
        layout.images.push_back({image, x + 1, y + 1});
        auto &page = layout.pages.back();
        page.count++;
        page.height = std::max(page.height, y + height);
        x += width;
        row_height = std::max(row_height, height);
    }
    size_t bytes = 0;
    for (auto &page : layout.pages) {
        page.height = std::min(max_size, PowerOfTwo(page.height));
        const size_t page_bytes = static_cast<size_t>(layout.width) * page.height;
        if (page_bytes > budget - bytes) return false;
        bytes += page_bytes;
    }
    return true;
}

}  // namespace ass_atlas
