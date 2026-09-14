from pathlib import Path

path = Path("app/src/main/res/values/strings_autoroot.xml")
text = path.read_text(encoding="utf-8")

if '<resources xmlns:tools="http://schemas.android.com/tools">' not in text:
    if text.count('<resources>') != 1:
        raise SystemExit("unexpected resources root in strings_autoroot.xml")
    text = text.replace(
        '<resources>',
        '<resources xmlns:tools="http://schemas.android.com/tools">',
        1,
    )

for name in (
    "autoroot_root_restored_modules_pending",
    "autoroot_apply_modules",
):
    old = f'<string name="{name}">'
    new = f'<string name="{name}" tools:ignore="MissingTranslation">'
    if old in text:
        text = text.replace(old, new, 1)
    elif new not in text:
        raise SystemExit(f"missing generated notification string {name}")

path.write_text(text, encoding="utf-8")
print("Configured English fallback for newly added Auto Root notification strings")
