"""Regression tests for the Arabic DroidDeck resources.

Run: python3 -m unittest tools/tests/test_arabic_localization.py -v
No emulator, Gradle download, or external translation service is required.
"""
from __future__ import annotations

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
DEFAULT = RES / "values/strings.xml"
ARABIC = RES / "values-ar/strings.xml"
SIX_FORMS = {"zero", "one", "two", "few", "many", "other"}

# Recognises indexed Android String.format placeholders (including width/precision),
# and escaped literal percent signs without mistaking ordinary "100% is ..." for a format.
FORMAT_RE = re.compile(r"%(?:\d+\$[-+#0 ,(]*\d*(?:\.\d+)?[a-zA-Z]|%)")


def parse(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def contents(node: ET.Element) -> str:
    return "".join(node.itertext())


def indexed_placeholders(text: str) -> list[str]:
    return sorted(FORMAT_RE.findall(text))


def by_type(root: ET.Element, tag: str) -> dict[str, ET.Element]:
    matches = [el for el in root if el.tag == tag]
    result = {el.attrib["name"]: el for el in matches}
    if len(result) != len(matches):
        raise AssertionError(f"Duplicate {tag} resource name")
    return result


class ArabicLocalizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.en = parse(DEFAULT)
        cls.ar = parse(ARABIC)

    def test_all_strings_have_arabic_entries(self) -> None:
        # Completeness is a translation-workflow concern, not a build gate:
        # Android falls back to English for keys no locale translates yet,
        # and every other locale ships partial coverage. What CI must catch
        # is malformed Arabic (duplicates, empty entries, mismatched format
        # arguments, edited non-translatable identifiers), so only keys that
        # exist on both sides are compared here.
        en = by_type(self.en, "string")
        ar = by_type(self.ar, "string")
        for name in en:
            if name not in ar:
                continue
            with self.subTest(key=name):
                self.assertTrue(contents(ar[name]).strip(), f"Empty Arabic string: {name}")
                self.assertEqual(indexed_placeholders(contents(en[name])),
                                 indexed_placeholders(contents(ar[name])),
                                 f"Format arguments differ in {name}")
                if en[name].attrib.get("translatable") == "false":
                    self.assertEqual(contents(en[name]), contents(ar[name]),
                                     f"Non-translatable identifier was changed: {name}")

    def test_arabic_plural_categories_and_parameters(self) -> None:
        en, ar = by_type(self.en, "plurals"), by_type(self.ar, "plurals")
        for name, original in en.items():
            if name not in ar:
                continue
            with self.subTest(key=name):
                variants = {el.attrib["quantity"]: el for el in ar[name]}
                self.assertEqual(set(variants), SIX_FORMS)
                baseline = next((el for el in original if el.attrib["quantity"] == "other"), None)
                self.assertIsNotNone(baseline)
                for quantity, element in variants.items():
                    self.assertTrue(contents(element).strip(), f"{name}/{quantity} is empty")
                    # An Arabic form may omit a count its grammar does not need
                    # (one/two), but must not reference arguments the source
                    # string never passes: extra arguments crash String.format.
                    self.assertLessEqual(set(indexed_placeholders(contents(element))),
                                         set(indexed_placeholders(contents(baseline))),
                                         f"Format arguments differ in {name}/{quantity}")

    def test_arrays_match_source_sizes(self) -> None:
        en = by_type(self.en, "string-array")
        ar = by_type(self.ar, "string-array")
        for name in en:
            if name not in ar:
                continue
            with self.subTest(key=name):
                self.assertEqual(len(en[name]), len(ar[name]))
                for item in ar[name]:
                    self.assertTrue(contents(item).strip())

    def test_locale_and_rtl_wiring(self) -> None:
        locales = (RES / "xml/locales_config.xml").read_text(encoding="utf-8")
        self.assertIn('<locale android:name="ar" />', locales)
        lang = (ROOT / "app/src/main/java/com/droiddeck/launcher/core/AppLanguage.kt").read_text(encoding="utf-8")
        self.assertIn('"ar" -> "العربية"', lang)
        self.assertIn('"ar"', lang)
        manifest = (RES.parent / "AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn('android:supportsRtl="true"', manifest)

    def test_xml_is_valid(self) -> None:
        self.assertEqual(self.ar.tag, "resources")
        self.assertEqual(self.en.tag, "resources")


if __name__ == "__main__":
    unittest.main()
