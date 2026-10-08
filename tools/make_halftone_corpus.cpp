// SPDX-License-Identifier: Apache-2.0
// Rebuild the original arithmetic-halftone document for #621. The encoder is
// agl/jbig2enc at d0dfca46216c98f11312a9c9f15615ed490cd7b3; only jbig2arith.cc
// and its header are needed. No external document or test-suite image is copied.
// c++ -std=c++17 -O2 -I/path/to/jbig2enc/src tools/make_halftone_corpus.cpp \
//     /path/to/jbig2enc/src/jbig2arith.cc -o /tmp/make-halftone
// /tmp/make-halftone corpus/pdf/kitepdf-jbig2-arithmetic-halftone.pdf
#include "jbig2arith.h"
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

static void be(std::string& out, unsigned value, unsigned count = 4) {
    for (unsigned i = count; i > 0; --i) out.push_back(static_cast<char>(value >> (8 * (i - 1))));
}

static std::string encode(const std::vector<uint8_t>& pixels, int width, int height) {
    jbig2enc_ctx ctx;
    jbig2enc_init(&ctx);
    jbig2enc_image(&ctx, pixels.data(), width, height, false);
    jbig2enc_final(&ctx);
    std::string out(jbig2enc_datasize(&ctx), '\0');
    jbig2enc_tobuffer(&ctx, reinterpret_cast<uint8_t*>(out.data()));
    jbig2enc_dealloc(&ctx);
    return out;
}

static std::string segment(unsigned number, unsigned type, unsigned page,
                           const std::string& data, unsigned reference = 0) {
    std::string out;
    be(out, number);
    be(out, type, 1);
    be(out, reference ? 0x23 : 0, 1); // one retained reference, or none, 7.2.4
    if (reference) be(out, reference, 1);
    be(out, page, 1);
    be(out, data.size());
    return out + data;
}

static std::string stream(const std::string& data, const std::string& entries = "") {
    return "<< " + entries + " /Length " + std::to_string(data.size()) + " >>\nstream\n" + data + "\nendstream";
}

int main(int argc, char** argv) {
    if (argc != 2) { std::cerr << "usage: make-halftone OUTPUT.pdf\n"; return 2; }
    constexpr unsigned width = 160, height = 120;
    const unsigned bayer[4][4] = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    std::vector<uint8_t> grid(width * height);
    for (unsigned y = 0; y < height; ++y) for (unsigned x = 0; x < width; ++x) {
        const unsigned shade = (x / 20) * 2 + 1;
        grid[y * width + x] = bayer[y % 4][x % 4] < shade ? 1 : 0;
    }
    // Two 1x1 patterns, white then black. For this two-pixel collective bitmap,
    // the pattern dictionary's adaptive pixel (-1,0) is zero at both positions,
    // just like the generic encoder's (3,-1) pixel. Other template pixels match.
    std::string patterns;
    be(patterns, 0, 1); // arithmetic coding, template 0, 7.4.4
    be(patterns, 1, 1); be(patterns, 1, 1); be(patterns, 1); // width, height, GRAYMAX
    patterns += encode({0, 1}, 2, 1);
    std::string page;
    be(page, width); be(page, height); be(page, 72); be(page, 72); be(page, 0, 1); be(page, 0, 2);
    std::string region;
    be(region, width); be(region, height); be(region, 0); be(region, 0); be(region, 0, 1);
    be(region, 0, 1); // HMMR=0, HTEMPLATE=0, HENABLESKIP=0, OR, white default
    be(region, width); be(region, height); be(region, 0); be(region, 0);
    be(region, 256, 2); be(region, 0, 2); // a 1x1 grid step in 8.8 fixed point
    // Two patterns need one grey-code plane, whose arithmetic generic template
    // is exactly the encoder's default template (6.6.2 and 6.6.5).
    region += encode(grid, width, height);
    const std::string data = segment(1, 48, 1, page) + segment(2, 16, 0, patterns) +
        segment(3, 22, 1, region, 2) + segment(4, 49, 1, "");
    const std::string content =
        "BT /F1 18 Tf 36 352 Td (JBIG2 arithmetic halftone) Tj ET\n"
        "BT /F1 10 Tf 36 326 Td (Original eight-tone ordered-dither chart, 160 by 120 pixels.) Tj ET\n"
        "q 160 0 0 120 120 166 cm /Im1 Do Q\n"
        "BT /F1 10 Tf 36 130 Td (Pattern dictionary: two 1 by 1 patterns, arithmetic template 0.) Tj ET\n"
        "BT /F1 10 Tf 36 112 Td (Immediate halftone region: one arithmetic grey-code plane.) Tj ET\n"
        "BT /F1 9 Tf 36 60 Td (KitePDF test corpus. Apache License 2.0. Issue 621.) Tj ET\n";
    std::vector<std::string> objects = {
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 400] /Resources << /Font << /F1 5 0 R >> /XObject << /Im1 6 0 R >> >> /Contents 4 0 R >>",
        stream(content), "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        stream(data, "/Type /XObject /Subtype /Image /Width 160 /Height 120 /ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /JBIG2Decode"),
        "<< /Title (JBIG2 arithmetic halftone chart) /Author (KitePDF contributors) /Subject (Original corpus fixture, Apache-2.0; ISO 14492 halftone segments) >>",
    };
    std::string pdf = "%PDF-1.7\n";
    std::vector<size_t> offsets;
    for (size_t i = 0; i < objects.size(); ++i) {
        offsets.push_back(pdf.size());
        pdf += std::to_string(i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n";
    }
    const size_t xref = pdf.size();
    pdf += "xref\n0 " + std::to_string(objects.size() + 1) + "\n0000000000 65535 f \n";
    for (auto offset : offsets) { std::ostringstream line; line << std::setfill('0') << std::setw(10) << offset << " 00000 n \n"; pdf += line.str(); }
    pdf += "trailer\n<< /Size " + std::to_string(objects.size() + 1) + " /Root 1 0 R /Info 7 0 R >>\nstartxref\n" + std::to_string(xref) + "\n%%EOF\n";
    std::ofstream file(argv[1], std::ios::binary);
    file.write(pdf.data(), pdf.size());
    if (!file) return 1;
    std::cout << pdf.size() << " bytes\n";
}
