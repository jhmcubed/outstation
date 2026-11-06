//! Windows' own notifications (11/06/2026, Jace: "a Windows notification when a friend goes live"): a toast, as any
//! program's, in the corner and then in the notification centre, "Moss Fern has gone live." with their title under
//! it; a click on it opens their Outside page (while the portal is open: it's the portal that hears the click).
//!
//! Windows shows a desktop program's toasts only when its Start menu shortcut carries the program's id (its
//! AppUserModelID): shortcut.rs puts AUMID on the shortcut it makes. Without the shortcut there's no toast, the
//! friends list says so, and the chat's line is all there is. Nothing is sent anywhere: a toast is Windows' own.

/// The id the Start menu's shortcut carries, and the toasts are sent under.
pub const AUMID: &str = "ghillie.outside-portal";

/// The toast's XML, its words escaped (only text: no pictures, no sound of its own beyond Windows' usual).
pub fn toast_xml(title: &str, body: &str) -> String {
    fn esc(s: &str) -> String {
        s.chars().filter(|c| !c.is_control()).map(|c| match c {
            '&' => "&amp;".to_string(), '<' => "&lt;".to_string(), '>' => "&gt;".to_string(),
            '"' => "&quot;".to_string(), '\'' => "&apos;".to_string(), c => c.to_string(),
        }).collect()
    }
    let line = if body.is_empty() { String::new() } else { format!("<text>{}</text>", esc(body)) };
    format!("<toast><visual><binding template=\"ToastGeneric\"><text>{}</text>{}</binding></visual></toast>", esc(title), line)
}

/// Show a toast, on a thread of its own; `open`: the page a click on it opens. Whether it could be shown is logged.
pub fn show(title: &str, body: &str, open: Option<String>) {
    if !crate::shortcut::exists() {
        return;
    }
    let xml = toast_xml(title, body);
    std::thread::spawn(move || {
        use windows::core::HSTRING;
        use windows::Data::Xml::Dom::XmlDocument;
        use windows::Foundation::TypedEventHandler;
        use windows::UI::Notifications::{ToastNotification, ToastNotificationManager};
        let shown = (|| -> windows::core::Result<ToastNotification> {
            let doc = XmlDocument::new()?;
            doc.LoadXml(&HSTRING::from(xml.as_str()))?;
            let toast = ToastNotification::CreateToastNotification(&doc)?;
            if let Some(url) = open {
                toast.Activated(&TypedEventHandler::new(move |_, _| {
                    crate::win::open_page(&url);
                    Ok(())
                }))?;
            }
            ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(AUMID))?.Show(&toast)?;
            Ok(toast)
        })();
        match shown {
            // (kept a while, so a click in the next minute reaches its handler; later, Windows only brings the portal up)
            Ok(toast) => {
                std::thread::sleep(std::time::Duration::from_secs(60));
                drop(toast);
            }
            Err(e) => crate::transmit::log(&format!("Windows wouldn't show a notification ({})", e)),
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_toast_says_only_its_words() {
        assert_eq!(toast_xml("Moss Fern has gone live.", "“bosses & <stuff>”"),
                   "<toast><visual><binding template=\"ToastGeneric\"><text>Moss Fern has gone live.</text><text>“bosses &amp; &lt;stuff&gt;”</text></binding></visual></toast>");
        assert!(!toast_xml("a\u{7}b", "").contains('\u{7}'));
    }
}
