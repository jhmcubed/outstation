"""The safety check: proof, from the built program itself, that it can't reach into another program, play the game,
or watch the keyboard (SAFETY.md says what each line means).

    py tests/safety_check.py target/release/outside-portal.exe       the exe's import table, and the client's source
    py tests/safety_check.py --list target/release/outside-portal.exe  every Windows function it imports

A Windows program names, in its import table, every function in Windows it links to: its own code's and every
library's. This reads that table straight out of the file (no tools needed) and fails if any function on the
NEVER list is there. Then it reads the client's own source for the same names, for anything loaded by hand
(GetProcAddress) and for ways of sending input or messages to another program's window. The release workflow runs
it on the very exe it publishes.

It also checks the program stands alone: every library it loads is part of Windows itself, so it runs on a PC with
nothing installed beside it (no Visual C++ runtime, no MinGW's, no codec). .cargo/config.toml builds the C runtime in.
"""

import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# Functions that could reach into another program, play for you, or read your keys. None may be imported.
NEVER = {
    # writing into or reading another program's memory, or running code inside it (injection)
    "WriteProcessMemory", "ReadProcessMemory", "VirtualAllocEx", "VirtualAlloc2", "VirtualProtectEx",
    "CreateRemoteThread", "CreateRemoteThreadEx", "RtlCreateUserThread", "NtCreateThreadEx", "QueueUserAPC",
    "NtQueueApcThread", "SetThreadContext", "GetThreadContext", "Wow64SetThreadContext", "SuspendThread",
    "NtReadVirtualMemory", "NtWriteVirtualMemory", "NtOpenProcess", "OpenThread", "DebugActiveProcess",
    "MiniDumpWriteDump", "CreateToolhelp32Snapshot_Module_",  # (the snapshot itself is only the process list)
    "Module32FirstW", "Module32NextW", "Heap32First", "Toolhelp32ReadProcessMemory",
    # sending keys or mouse clicks, to the game or anything else
    "SendInput", "keybd_event", "mouse_event", "SendInput_", "BlockInput",
    # watching the keyboard or mouse everywhere (keyloggers' ways)
    "SetWindowsHookExA", "SetWindowsHookExW", "GetAsyncKeyState", "GetKeyboardState",
    # attaching to another program's input
    "AttachThreadInput",
}

# Imported by the libraries the window is built on, each for this program's own window only, and why. The check
# fails on any never-list function not here, and the client's own source may use none of them (check_source).
LIBRARIES = {
    "SendInput": "winit (the window library) sends one pretend Alt key when asked to bring its own window to the "
                 "front, a workaround for Windows' focus rules (winit src/platform_impl/windows/window.rs, "
                 "force_window_active). The client never asks it to: it brings its window forward with Windows' "
                 "own SetForegroundWindow. muda (the tray menu) has it for its ready-made copy and paste items, "
                 "which the client doesn't use (muda src/platform_impl/windows/mod.rs).",
    "GetAsyncKeyState": "winit reads whether Shift, Ctrl and Alt are held when its own window gets the keyboard "
                        "focus, so its keys start right (winit src/platform_impl/windows/keyboard.rs).",
    "GetKeyboardState": "winit, the same: the keys' state as its own window's messages arrive "
                        "(winit src/platform_impl/windows/keyboard.rs).",
    "Module32FirstW": "Rust's standard library lists its own program's parts (process 0: itself) to write a crash "
                      "report's backtrace.",
    "Module32NextW": "the same as Module32FirstW.",
}

# In the client's own source, these must not appear either (PostMessage/SendMessage are allowed only in the window
# code's own lines listed below, which talk to this program's own windows).
SOURCE_NEVER = sorted(NEVER) + ["GetProcAddress", "LoadLibrary", "OpenProcess", "PostMessage", "SendMessage",
                                "SetForegroundWindow_to_runelite"]
# what the client's source may use, with why (SAFETY.md)
SOURCE_ALLOWED = {
    "SetForegroundWindow": "brings this program's own window to the front when it's opened twice (win.rs show_window)",
}


# Libraries that come with Windows 10 and 11 (in System32 on every PC). Anything else the exe needs would have to be
# installed beside it, so it fails the check (add a library here only with where Windows ships it).
WINDOWS_DLLS = {
    "kernel32.dll", "kernelbase.dll", "ntdll.dll", "user32.dll", "gdi32.dll", "advapi32.dll", "shell32.dll",
    "shlwapi.dll", "ole32.dll", "oleaut32.dll", "combase.dll", "comctl32.dll", "comdlg32.dll", "uxtheme.dll",
    "dwmapi.dll", "imm32.dll", "winmm.dll", "version.dll", "userenv.dll", "ws2_32.dll", "iphlpapi.dll",
    "bcrypt.dll", "bcryptprimitives.dll", "crypt32.dll", "secur32.dll", "ncrypt.dll", "d3d11.dll", "dxgi.dll",
    "d3dcompiler_47.dll", "opengl32.dll", "mfplat.dll", "mfreadwrite.dll", "mf.dll", "propsys.dll", "avrt.dll",
    "mmdevapi.dll", "ksuser.dll", "psapi.dll", "dbghelp.dll", "setupapi.dll", "cfgmgr32.dll", "hid.dll",
    "powrprof.dll", "winhttp.dll", "wtsapi32.dll", "msvcrt.dll", "ucrtbase.dll", "synchronization.dll",
    "dwrite.dll", "d2d1.dll", "windowscodecs.dll", "oleacc.dll", "uiautomationcore.dll", "msimg32.dll",
    "winspool.drv", "netapi32.dll", "rpcrt4.dll", "sechost.dll", "shcore.dll", "dcomp.dll",
    "coremessaging.dll",  # (Windows Graphics Capture's dispatcher queue, in System32 since Windows 10)
}
# Windows' own API sets (api-ms-win-crt-*, the Universal C Runtime among them, is part of Windows 10 and 11)
WINDOWS_PREFIXES = ("api-ms-win-", "ext-ms-win-")
# The usual ones that aren't: each would need installing (named, so the failure says what it is)
NOT_WINDOWS = {
    "vcruntime": "Microsoft's Visual C++ runtime (a Redistributable): .cargo/config.toml's crt-static builds it in",
    "msvcp": "Microsoft's Visual C++ library (a Redistributable): crt-static, as above",
    "concrt": "Microsoft's Visual C++ runtime, as above",
    "vccorlib": "Microsoft's Visual C++ runtime, as above",
    "libgcc": "MinGW's runtime: link it statically",
    "libstdc++": "MinGW's C++ library: link it statically",
    "libwinpthread": "MinGW's threads: link it statically",
    "libunwind": "llvm-mingw's runtime: link it statically",
    "libc++": "llvm-mingw's C++ library: link it statically",
    "opus": "libopus as a DLL: it should be built in (the opus crate does)",
}


def outside_windows(dlls):
    """[(dll, why)] for every library the exe loads that doesn't come with Windows."""
    out = []
    for dll in sorted(dlls):
        if dll in WINDOWS_DLLS or dll.startswith(WINDOWS_PREFIXES):
            continue
        why = next((w for k, w in NOT_WINDOWS.items() if dll.startswith(k)), "not known to come with Windows")
        out.append((dll, why))
    return out


def rva_to_offset(sections, rva):
    for va, size, raw, rawsize in sections:
        if va <= rva < va + max(size, rawsize):
            return rva - va + raw
    raise ValueError("an address outside every section")


def pe_parts(data):
    """(the data directories' offset, the sections) of a PE file."""
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("not a Windows program")
    nsec = struct.unpack_from("<H", data, pe + 6)[0]
    opt = pe + 24
    dd = opt + (112 if struct.unpack_from("<H", data, opt)[0] == 0x20B else 96)
    sec = opt + struct.unpack_from("<H", data, pe + 20)[0]
    sections = []
    for i in range(nsec):
        size, va, rawsize, raw = struct.unpack_from("<IIII", data, sec + 40 * i + 8)
        sections.append((va, size, raw, rawsize))
    return dd, sections


def resource_kinds(path):
    """The kinds of resource the exe carries, by their numbers (3 an icon, 14 an icon's group, 16 the version block):
    the resource directory's first level (11/06/2026: the exe's own icon, build.rs)."""
    data = open(path, "rb").read()
    dd, sections = pe_parts(data)
    rva = struct.unpack_from("<I", data, dd + 2 * 8)[0]
    if not rva:
        return set()
    off = rva_to_offset(sections, rva)
    named, ids = struct.unpack_from("<HH", data, off + 12)
    kinds = set()
    for i in range(named + ids):
        name = struct.unpack_from("<I", data, off + 16 + 8 * i)[0]
        if not name & 0x80000000:
            kinds.add(name)
    return kinds


def imports(path):
    """{dll: [function names]} from a PE file's import table (and its delay-load table)."""
    data = open(path, "rb").read()
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("not a Windows program")
    nsec = struct.unpack_from("<H", data, pe + 6)[0]
    opt = pe + 24
    magic = struct.unpack_from("<H", data, opt)[0]
    plus = magic == 0x20B
    dd = opt + (112 if plus else 96)
    sec = opt + struct.unpack_from("<H", data, pe + 20)[0]
    sections = []
    for i in range(nsec):
        s = sec + 40 * i
        size, va, rawsize, raw = struct.unpack_from("<IIII", data, s + 8)
        sections.append((va, size, raw, rawsize))

    def cstr(off):
        return data[off:data.index(b"\0", off)].decode("latin-1")

    def names(thunk_rva):
        out, off = [], rva_to_offset(sections, thunk_rva)
        step, flag = (8, 1 << 63) if plus else (4, 1 << 31)
        while True:
            v = struct.unpack_from("<Q" if plus else "<I", data, off)[0]
            if v == 0:
                return out
            out.append("#%d" % (v & 0xFFFF) if v & flag else cstr(rva_to_offset(sections, v & 0x7FFFFFFF) + 2))
            off += step

    found = {}
    imp_rva = struct.unpack_from("<I", data, dd + 8)[0]
    if imp_rva:
        off = rva_to_offset(sections, imp_rva)
        while True:
            oft, _, _, name, ft = struct.unpack_from("<IIIII", data, off)
            if name == 0:
                break
            found.setdefault(cstr(rva_to_offset(sections, name)).lower(), []).extend(names(oft or ft))
            off += 20
    delay_rva = struct.unpack_from("<I", data, dd + 13 * 8)[0]
    if delay_rva:
        off = rva_to_offset(sections, delay_rva)
        while True:
            fields = struct.unpack_from("<IIIIIIII", data, off)
            if fields[1] == 0:
                break
            found.setdefault(cstr(rva_to_offset(sections, fields[1])).lower(), []).extend(names(fields[4]))
            off += 32
    return found


def check_exe(path):
    got = imports(path)
    flagged = sorted((dll, f) for dll, fs in got.items() for f in fs if f in NEVER or f.rstrip("AW") in NEVER)
    bad = [x for x in flagged if x[1] not in LIBRARIES]
    known = [x for x in flagged if x[1] in LIBRARIES]
    return got, bad, known


def check_source():
    bad = []
    src = os.path.join(ROOT, "client", "src")
    for name in sorted(os.listdir(src)):
        text = open(os.path.join(src, name), encoding="utf-8").read()
        code = "\n".join(l.split("//")[0] for l in text.splitlines())    # (comments may name things)
        for word in SOURCE_NEVER:
            for m in re.finditer(r"\b%s[AW]?\b" % re.escape(word), code):
                line = code[:m.start()].count("\n") + 1
                bad.append("%s:%d uses %s" % (name, line, m.group(0)))
    return bad


# The only addresses written into the client: everything else it reaches is one you gave it (the relay from your
# attach code, your Discord webhook if you set one).
ADDRESSES = {
    "https://api.github.com/repos/jhmcubed/outside-portal/releases/latest": "is there a newer version? (check_updates)",
    "https://relay.ghillie.workers.dev": "the listening post's relay: where asking for a portal goes (ask.rs HOME_RELAY), and the "
                                         "friends list's open portals before you're attached",
    "https://ghillie.wiki": "named in the Discord user agent Dink's posts are forwarded with; and the Outside pages the "
                            "friends list opens in your browser when you click a name (win.rs open_page: ghillie.wiki's only)",
    "https://github.com/jhmcubed/outside-portal/blob/main/SAFETY.md": "this page, opened in your browser from the attach "
                                                                     "screen's link (win.rs open_page)",
    "http://127.0.0.1": "Dink's address on this PC, shown to you",
    "ws://127.0.0.1": "a relay of your own on this PC (relay-py), only when you choose one by hand",
    "https://discord.com/api/webhooks/": "the webhook box's hint, an example only",
}


def check_network():
    """Every address in the client's source is one of ADDRESSES; it listens on this PC only (127.0.0.1)."""
    bad = []
    src = os.path.join(ROOT, "client", "src")
    for name in sorted(os.listdir(src)):
        text = open(os.path.join(src, name), encoding="utf-8").read().split("#[cfg(test)]")[0]   # (not the tests)
        for m in re.finditer(r"(?:https?|wss?)://[^\s\"'{}<>)…]*", text):
            url = m.group(0).rstrip(".,;:")
            if not any(url.startswith(a) for a in ADDRESSES) and url not in ("ws://", "wss://", "http://", "https://"):
                line = text[:m.start()].count(chr(10)) + 1
                bad.append("%s:%d names %s" % (name, line, url))
        for m in re.finditer(r"0\.0\.0\.0|\[::\]|Server::http\((?!\(\"127\.0\.0\.1\")", text):
            line = text[:m.start()].count(chr(10)) + 1
            bad.append("%s:%d may listen beyond this PC" % (name, line))
    return bad


def main(argv):
    listing = "--list" in argv
    needs_icon = "--needs-icon" in argv    # (a release, built with MSVC, carries build.rs's icon and version block)
    paths = [a for a in argv if not a.startswith("--")] or [os.path.join(ROOT, "target", "release", "outside-portal.exe")]
    failed = False
    for p in paths:
        got, bad, known = check_exe(p)
        n = sum(len(v) for v in got.values())
        if listing:
            for dll in sorted(got):
                print("%s: %s" % (dll, ", ".join(sorted(got[dll]))))
        print("%s: %d Windows functions from %d libraries; on the never list: %s"
              % (os.path.basename(p), n, len(got), ", ".join("%s!%s" % b for b in bad) or "none"))
        for dll, f in known:
            print("  (%s!%s: the libraries' own, for this program alone: %s)" % (dll, f, LIBRARIES[f]))
        failed |= bool(bad)
        extra = outside_windows(got)
        print("  it loads: %s" % ", ".join(sorted(got)))
        print("  stands alone: %s" % ("; ".join("%s (%s)" % e for e in extra) if extra
                                       else "yes, every library it loads comes with Windows"))
        failed |= bool(extra)
        kinds = resource_kinds(p)
        has = {3, 14, 16} <= kinds
        print("  its icon and name in Windows: %s" % ("yes (its icon, and outside-portal in its details)" if has else
                                                       "no (build.rs adds them in an MSVC build only)"))
        failed |= needs_icon and not has
    src = check_source()
    print("the client's source: %s" % ("; ".join(src) if src else "none of them, and nothing loaded by hand"))
    failed |= bool(src)
    net = check_network()
    print("its addresses: %s" % ("; ".join(net) if net else "only the relay you attach to, your Discord webhook if you set one, "
                                                              "GitHub's releases, and the pages it opens in your browser (ghillie.wiki's, "
                                                              "and its own SAFETY.md); it listens on 127.0.0.1 only"))
    failed |= bool(net)
    print("FAILED" if failed else "passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
