//! Prints anisette headers generated from the Apple libs under the given
//! config directory: `cargo run -p ottjni --example headers -- <dir>`.
fn main() {
    let dir = std::env::args().nth(1).expect("usage: headers <config-dir>");
    let cfg = ottjni::AnisetteConfig::new(dir.into());
    match ottjni::fetch_headers(&cfg) {
        Ok(h) => {
            let mut keys: Vec<_> = h.keys().collect();
            keys.sort();
            for k in keys {
                println!("{k}: {} chars", h[k].len());
            }
        }
        Err(e) => {
            eprintln!("error: {e}");
            std::process::exit(1);
        }
    }
}
