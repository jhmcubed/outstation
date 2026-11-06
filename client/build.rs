//! The exe's own icon and its name in Windows (11/06/2026, Jace: "the exe's own icon"): the ✼ (assets/icon.png) and
//! a version block (what Explorer's details, Task Manager and Windows' prompts call it), as a compiled resource file
//! written here, byte by byte, and handed to the linker. MSVC's linker takes a .res file as it is, so releases
//! (built with MSVC on GitHub) carry both; other toolchains (gnullvm on the keeper's PC, gnu in a test build) go
//! without, as before, so nothing else is needed to build. Nothing here runs at run time.

use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=assets/icon.png");
    println!("cargo:rerun-if-changed=build.rs");
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() != Ok("msvc") {
        return;
    }
    let png = std::fs::read("assets/icon.png").expect("assets/icon.png");
    let version = std::env::var("CARGO_PKG_VERSION").unwrap_or_default();
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("outside-portal.res");
    std::fs::write(&out, res(&png, &version)).expect("the resource file");
    println!("cargo:rustc-link-arg-bins={}", out.display());
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
        s("FileDescription", "outside-portal"),
        s("ProductName", "outside-portal"),
        s("FileVersion", version),
        s("ProductVersion", version),
        s("OriginalFilename", "outside-portal.exe"),
        s("InternalName", "outside-portal"),
        s("Comments", "the portal: your RuneLite, live on your Outside page (ghillie.wiki)"),
        s("LegalCopyright", "MIT licence: github.com/jhmcubed/outside-portal"),
    ]);
    let string_info = block("StringFileInfo", &[], 0, true, &[strings]);
    let translation = block("Translation", &[0x09, 0x04, 0xB0, 0x04], 4, false, &[]);
    let var_info = block("VarFileInfo", &[], 0, true, &[translation]);
    block("VS_VERSION_INFO", &fixed, fixed.len() as u16, false, &[string_info, var_info])
}
