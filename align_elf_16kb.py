#!/usr/bin/env python3
"""Align ELF LOAD segments to 16KB page size (65536 bytes) for Android 15+ compatibility."""

import struct
import sys
import os

TARGET_ALIGN = 65536


def align_elf(filepath):
    with open(filepath, 'rb') as f:
        data = bytearray(f.read())

    if len(data) < 52 or data[:4] != b'\x7fELF':
        return False

    is64 = data[4] == 2
    endian = '<'
    phdr_size = 56 if is64 else 32

    if is64:
        phoff = struct.unpack_from(endian + 'Q', data, 32)[0]
        phnum = struct.unpack_from(endian + 'H', data, 56)[0]
    else:
        phoff = struct.unpack_from(endian + 'I', data, 28)[0]
        phnum = struct.unpack_from(endian + 'H', data, 44)[0]

    modified = False
    for i in range(phnum):
        off = phoff + i * phdr_size
        p_type = struct.unpack_from(endian + 'I', data, off)[0]

        if p_type == 1 or p_type == 0x6474e552:  # PT_LOAD or PT_GNU_RELRO
            align_off = off + (48 if is64 else 28)
            if is64:
                cur = struct.unpack_from(endian + 'Q', data, align_off)[0]
                if cur < TARGET_ALIGN:
                    struct.pack_into(endian + 'Q', data, align_off, TARGET_ALIGN)
                    modified = True
            else:
                cur = struct.unpack_from(endian + 'I', data, align_off)[0]
                if cur < TARGET_ALIGN:
                    struct.pack_into(endian + 'I', data, align_off, TARGET_ALIGN)
                    modified = True

    if modified:
        with open(filepath, 'wb') as f:
            f.write(data)
        print(f'  aligned: {os.path.basename(filepath)}')
    return modified


def main():
    if len(sys.argv) < 2:
        print('Usage: align_elf_16kb.py <merged_native_libs_dir>')
        sys.exit(1)

    root = sys.argv[1]
    if not os.path.isdir(root):
        print(f'Directory not found: {root}')
        sys.exit(0)

    count = 0
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            if fn.endswith('.so'):
                fp = os.path.join(dirpath, fn)
                if align_elf(fp):
                    count += 1

    print(f'Aligned {count} .so file(s)')


if __name__ == '__main__':
    main()
