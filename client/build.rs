//! The exe's own icon and its name in Windows (11/06/2026, Jace: "the exe's own icon"): the ✼ (assets/icon.png) and
//! a version block (what Explorer's details, the taskbar, Task Manager and Windows' prompts call it), as a compiled
//! resource file written here, byte by byte. MSVC's linker (the releases, built on GitHub) takes the .res file as it
//! is. The GNU-style toolchains (gnullvm on the keeper's PC, linked by llvm-mingw; gnu in a test build) need it as an
//! object: the toolchain's own resource compiler (windres, llvm-mingw's or binutils') converts it (11/07/2026, Jace:
//! his own build had a blank icon and an old name in the taskbar). Without one, the build goes without, and says so.
//! Nothing here runs at run time.

use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=assets/icon.png");
    println!("cargo:rerun-if-changed=build.rs");
    let png = std::fs::read("assets/icon.png").expect("assets/icon.png");
    let version = std::env::var("CARGO_PKG_VERSION").unwrap_or_default();
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("outstation.res");
    std::fs::write(&out, res(&png, &version)).expect("the resource file");
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc") {
        println!("cargo:rustc-link-arg-bins={}", out.display());
        return;
    }
    // (a GNU-style linker: the .res as a COFF object, by whichever resource compiler is on the PATH)
    let obj = out.with_extension("o");
    let arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
    let tools = [format!("{}-w64-mingw32-windres", arch), "llvm-windres".to_string(), "windres".to_string()];
    for tool in &tools {
        let ok = std::process::Command::new(tool).args(["--input-format=res", "--output-format=coff", "-i"]).arg(&out).arg("-o").arg(&obj)
            .status().is_ok_and(|s| s.success());
        if ok {
            println!("cargo:rustc-link-arg-bins={}", obj.display());
            return;
        }
    }
    println!("cargo:warning=no resource compiler (windres) found: this build goes without its icon and its name in Windows");
}

/// a resource file: an empty entry first (the format's mark), then the icon, its group, and the version block
fn res(png: &[u8], version: &str) -> Vec<u8> {
    let (w, h) = (u32::from_be_bytes(png[16..20].try_into().unwrap()), u32::from_be_bytes(png[20..24].try_into().unwrap()));
    let mut f = Vec::new();
    entry(&mut f, 0, 0, 0, &[]);
    entry(&mut f, 3, 1, 0x1010, png); // RT_ICON 1: the PNG itself (as Windows has read since Vista)
    let mut group = vec![0, 0, 1, 0, 1, 0]; // reserved, type 1 (icons), one
    group.push(if w >= 256 { 0 } else { w as u8 });
    group.push(if h >= 256 { 0 } else { h as u8 });
    group.extend_from_slice(&[0, 0, 1, 0, 32, 0]); // no palette, reserved, one plane, 32 bits a pixel
    group.extend_from_slice(&(png.len() as u32).to_le_bytes());
    group.extend_from_slice(&1u16.to_le_bytes()); // its RT_ICON's id
    entry(&mut f, 14, 1, 0x1030, &group); // RT_GROUP_ICON 1: the exe's icon
    entry(&mut f, 16, 1, 0x0030, &version_info(version)); // RT_VERSION 1
    f
}

fn pad4(v: &mut Vec<u8>) {
    while v.len() % 4 != 0 {
        v.push(0);
    }
}

fn entry(f: &mut Vec<u8>, kind: u16, id: u16, flags: u16, data: &[u8]) {
    f.extend_from_slice(&(data.len() as u32).to_le_bytes());
    f.extend_from_slice(&32u32.to_le_bytes()); // the header's size: ordinals for both its type and its name
    f.extend_from_slice(&[0xFF, 0xFF]);
    f.extend_from_slice(&kind.to_le_bytes());
    f.extend_from_slice(&[0xFF, 0xFF]);
    f.extend_from_slice(&id.to_le_bytes());
    f.extend_from_slice(&0u32.to_le_bytes()); // data version
    f.extend_from_slice(&flags.to_le_bytes());
    f.extend_from_slice(&(if kind == 0 { 0u16 } else { 0x0409u16 }).to_le_bytes()); // English (US)
    f.extend_from_slice(&0u32.to_le_bytes()); // version
    f.extend_from_slice(&0u32.to_le_bytes()); // characteristics
    f.extend_from_slice(data);
    pad4(f);
}

fn utf16z(s: &str) -> Vec<u8> {
    s.encode_utf16().chain(std::iter::once(0)).flat_map(|c| c.to_le_bytes()).collect()
}

/// one block of the version resource: its length, its value's length, whether it's text, its key, then its value and
/// its children, each on a 4-byte boundary
fn block(key: &str, value: &[u8], value_len: u16, text: bool, children: &[Vec<u8>]) -> Vec<u8> {
    let mut b = vec![0, 0];
    b.extend_from_slice(&value_len.to_le_bytes());
    b.extend_from_slice(&(text as u16).to_le_bytes());
    b.extend_from_slice(&utf16z(key));
    pad4(&mut b);
    b.extend_from_slice(value);
    for c in children {
        pad4(&mut b);
        b.extend_from_slice(c);
    }
    let n = b.len() as u16;
    b[0..2].copy_from_slice(&n.to_le_bytes());
    b
}

fn version_info(version: &str) -> Vec<u8> {
    let n: Vec<u16> = version.split('.').map(|x| x.parse().unwrap_or(0)).chain(std::iter::repeat(0)).take(4).collect();
    let (ms, ls) = (((n[0] as u32) << 16) | n[1] as u32, ((n[2] as u32) << 16) | n[3] as u32);
    let mut fixed = Vec::new();
    for x in [0xFEEF04BDu32, 0x00010000, ms, ls, ms, ls, 0x3F, 0, 0x00040004, 1, 0, 0, 0] {
        fixed.extend_from_slice(&x.to_le_bytes()); // signature, struct version, file and product versions, flags, NT, an app
    }
    let s = |k: &str, v: &str| {
        let val = utf16z(v);
        block(k, &val, (val.len() / 2) as u16, true, &[])
    };
    let strings = block("040904B0", &[], 0, true, &[
        s("FileDescription", "⊂λ,ｍ⊙ outstation"),
        s("ProductName", "⊂λ,ｍ⊙ outstation"),
        s("FileVersion", version),
        s("ProductVersion", version),
        s("OriginalFilename", "outstation.exe"),
        s("InternalName", "outstation"),
        s("Comments", "your RuneLite, live on your Outside page (ghillie.wiki)"),
        s("LegalCopyright", "MIT licence: github.com/jhmcubed/outstation"),
        // (Jagex's Fan Content Policy's words, 11/06/2026)
        s("LegalTrademarks", "Created using intellectual property belonging to Jagex Limited under the terms of Jagex's Fan Content Policy. This content is not endorsed by or affiliated with Jagex."),
    ]);
    let string_info = block("StringFileInfo", &[], 0, true, &[strings]);
    let translation = block("Translation", &[0x09, 0x04, 0xB0, 0x04], 4, false, &[]);
    let var_info = block("VarFileInfo", &[], 0, true, &[translation]);
    block("VS_VERSION_INFO", &fixed, fixed.len() as u16, false, &[string_info, var_info])
}
