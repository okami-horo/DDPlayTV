"""Generate tiny, self-authored Matroska subtitle fixtures; no external media/tools required."""

from pathlib import Path
import struct
import zlib


def element(identifier, data):
    id_bytes = identifier.to_bytes((identifier.bit_length() + 7) // 8, "big")
    length = next(n for n in range(1, 9) if len(data) < (1 << (7 * n)) - 1)
    return id_bytes + (len(data) | (1 << (7 * length))).to_bytes(length, "big") + data


def number(identifier, value):
    return element(identifier, value.to_bytes(max(1, (value.bit_length() + 7) // 8), "big"))


def fixture(mode):
    header = (
        "[Script Info]\nScriptType: v4.00+\nPlayResX: 640\nPlayResY: 360\n"
        "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, "
        "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, "
        "Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
        "Style: Default,Roboto,32,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,"
        "100,100,0,0,1,2,0,2,10,10,20,1\n[Events]\n"
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
    ).encode()
    encoding = b""
    if mode != "plain":
        algorithm = number(0x4254, 0) if mode == "zlib-explicit" else b""
        encoding = element(0x6D80, element(0x6240, element(0x5034, algorithm)))
    tracks = b""
    blocks = b""
    for track_id, title, chinese in ((1, "简体 CHS", "简体"), (2, "繁體 CHT", "繁體")):
        tracks += element(0xAE, number(0xD7, track_id) + number(0x73C5, track_id) +
                          number(0x83, 17) + number(0x88, int(track_id == 1)) +
                          element(0x536E, title.encode()) + element(0x22B59C, b"zho") +
                          element(0x86, b"S_TEXT/ASS") + element(0x63A2, header) + encoding)
        for index, (start, duration, text) in enumerate(((0, 1000, "Before seek"), (3000, 1000, "After seek"))):
            packet = f"{index},0,Default,,0,0,0,,{text} {chinese}".encode()
            if mode != "plain":
                packet = zlib.compress(packet)
            block = bytes([0x80 | track_id]) + struct.pack(">hB", start, 0) + packet
            blocks += element(0xA0, element(0xA1, block) + number(0x9B, duration))
    info = number(0x2AD7B1, 1_000_000) + element(0x4489, struct.pack(">d", 5000.0))
    ebml = number(0x4286, 1) + number(0x42F7, 1) + number(0x42F2, 4) + number(0x42F3, 8)
    ebml += element(0x4282, b"matroska") + number(0x4287, 4) + number(0x4285, 2)
    return element(0x1A45DFA3, ebml) + element(
        0x18538067, element(0x1549A966, info) + element(0x1654AE6B, tracks) +
        element(0x1F43B675, number(0xE7, 0) + blocks))


if __name__ == "__main__":
    target = Path(__file__).resolve().parents[2] / "player_component/src/androidTest/assets/media3"
    for mode in ("plain", "zlib-explicit", "zlib-default"):
        (target / f"subtitle-{mode}.mkv").write_bytes(fixture(mode))
