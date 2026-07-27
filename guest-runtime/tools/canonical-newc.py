#!/usr/bin/env python3
"""Create and verify PhoneCode's deterministic gzip-compressed newc archive."""

import gzip
import os
import stat
import sys
from pathlib import Path


MAGIC = b"070701"
TRAILER = "TRAILER!!!"


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"canonical newc: {message}")


def pad4(data: bytearray) -> None:
    data.extend(b"\0" * ((-len(data)) % 4))


def entries(root: Path):
    yield Path(".")
    yield from sorted(
        (path.relative_to(root) for path in root.rglob("*")),
        key=lambda path: os.fsencode(str(path)),
    )


def append_entry(
    archive: bytearray,
    inode: int,
    name: str,
    mode: int,
    payload: bytes,
    epoch: int,
) -> None:
    encoded_name = os.fsencode(name) + b"\0"
    fields = (
        inode,
        mode,
        0,
        0,
        1,
        epoch,
        len(payload),
        0,
        0,
        0,
        0,
        len(encoded_name),
        0,
    )
    archive.extend(MAGIC)
    archive.extend("".join(f"{value:08x}" for value in fields).encode("ascii"))
    archive.extend(encoded_name)
    pad4(archive)
    archive.extend(payload)
    pad4(archive)


def create(root: Path, output: Path, epoch: int) -> None:
    if not root.is_dir():
        fail(f"input root is not a directory: {root}")
    archive = bytearray()
    inode = 1
    for relative in entries(root):
        source = root if relative == Path(".") else root / relative
        metadata = source.lstat()
        mode = stat.S_IMODE(metadata.st_mode)
        if stat.S_ISDIR(metadata.st_mode):
            mode |= stat.S_IFDIR
            payload = b""
        elif stat.S_ISREG(metadata.st_mode):
            mode |= stat.S_IFREG
            payload = source.read_bytes()
        elif stat.S_ISLNK(metadata.st_mode):
            mode |= stat.S_IFLNK
            payload = os.fsencode(os.readlink(source))
        else:
            fail(f"unsupported file type: {relative}")
        append_entry(archive, inode, str(relative), mode, payload, epoch)
        inode += 1
    append_entry(archive, inode, TRAILER, 0, b"", epoch)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw:
        with gzip.GzipFile(
            filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=epoch
        ) as compressed:
            compressed.write(archive)


def hex_field(data: bytes, offset: int) -> int:
    try:
        return int(data[offset : offset + 8], 16)
    except ValueError:
        fail("header contains a non-hexadecimal field")


def align4(offset: int) -> int:
    return offset + ((-offset) % 4)


def verify(archive_path: Path, expected_epoch: int) -> None:
    try:
        data = gzip.decompress(archive_path.read_bytes())
    except (OSError, EOFError) as error:
        fail(f"invalid deterministic gzip stream: {error}")
    offset = 0
    expected_inode = 1
    seen = []
    while True:
        if data[offset : offset + 6] != MAGIC:
            fail("entry does not use newc format")
        header = data[offset : offset + 110]
        if len(header) != 110:
            fail("truncated newc header")
        values = [hex_field(header, 6 + field * 8) for field in range(13)]
        (
            inode,
            mode,
            uid,
            gid,
            nlink,
            mtime,
            size,
            devmajor,
            devminor,
            rdevmajor,
            rdevminor,
            namesize,
            check,
        ) = values
        if inode != expected_inode:
            fail("inode sequence is not canonical")
        if uid != 0 or gid != 0 or nlink != 1:
            fail("owner or link metadata is not canonical")
        if mtime != expected_epoch:
            fail("entry timestamp is not SOURCE_DATE_EPOCH")
        if any((devmajor, devminor, rdevmajor, rdevminor, check)):
            fail("device or checksum metadata is not canonical")
        if namesize < 2:
            fail("entry name is empty")
        name_start = offset + 110
        name_end = name_start + namesize
        encoded_name = data[name_start:name_end]
        if len(encoded_name) != namesize or not encoded_name.endswith(b"\0"):
            fail("entry name is truncated")
        try:
            name = os.fsdecode(encoded_name[:-1])
        except UnicodeError:
            fail("entry name is not valid")
        payload_start = align4(name_end)
        payload_end = payload_start + size
        if len(data[payload_start:payload_end]) != size:
            fail("entry payload is truncated")
        offset = align4(payload_end)
        if name == TRAILER:
            if mode != 0 or size != 0:
                fail("trailer is not canonical")
            if any(data[offset:]):
                fail("nonzero data follows trailer")
            break
        file_type = stat.S_IFMT(mode)
        if file_type not in (stat.S_IFDIR, stat.S_IFREG, stat.S_IFLNK):
            fail(f"unsupported archived file type: {name}")
        permissions = stat.S_IMODE(mode)
        if file_type == stat.S_IFDIR and permissions != 0o755:
            fail(f"directory mode is not canonical: {name}")
        if file_type == stat.S_IFREG:
            expected_permissions = (
                0o755
                if name in ("init", "bin/busybox", "phonecode-guestd")
                else 0o644
            )
            if permissions != expected_permissions:
                fail(f"regular-file mode is not canonical: {name}")
        if file_type == stat.S_IFLNK and permissions != 0o777:
            fail(f"symlink mode is not canonical: {name}")
        if name in seen:
            fail(f"duplicate archive entry: {name}")
        seen.append(name)
        expected_inode += 1
    if seen != sorted(seen, key=os.fsencode):
        fail("entries are not bytewise sorted")
    for name in seen:
        print(name)


def main(argv) -> None:
    if len(argv) != 5 or argv[1] != "create":
        if len(argv) == 4 and argv[1] == "verify":
            verify(Path(argv[2]), int(argv[3]))
            return
        fail(
            "usage: canonical-newc.py create ROOT OUTPUT EPOCH "
            "| verify ARCHIVE EPOCH"
        )
    create(Path(argv[2]), Path(argv[3]), int(argv[4]))


if __name__ == "__main__":
    main(sys.argv)
