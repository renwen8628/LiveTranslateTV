import json
import tempfile
import unittest
from pathlib import Path

from glossary import Glossary, GlossaryStore, clean_game_name


class GlossaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_clean_game_name(self):
        self.assertEqual(clean_game_name("Call of Duty®: Black Ops 6™"), "Call of Duty: Black Ops 6")
        self.assertIsNone(clean_game_name("  ®  "))
        self.assertIsNone(clean_game_name(None))

    def test_learn_keeps_first_translation_and_persists(self):
        g = Glossary("Call of Duty: Black Ops 6", self.dir)
        self.assertEqual(g.learn({"Adler": "阿德勒", "Woods": "伍兹"}), {"Adler": "阿德勒", "Woods": "伍兹"})
        self.assertEqual(g.learn({"adler": "艾德勒", "Mason": "梅森"}), {"Mason": "梅森"})
        data = json.loads((self.dir / "call-of-duty-black-ops-6.json").read_text(encoding="utf-8"))
        self.assertEqual(data["terms"], {"Adler": "阿德勒", "Mason": "梅森", "Woods": "伍兹"})
        self.assertEqual(Glossary("Call of Duty: Black Ops 6", self.dir).terms["Adler"], "阿德勒")

    def test_relevant_matches_whole_words_case_insensitively(self):
        g = Glossary("Game", self.dir)
        g.learn({"Park": "朴", "Task Force 141": "141特遣队", "Al": "阿尔"})
        found = g.relevant(["Get to the park, task force 141!", "Also here"])
        self.assertEqual(found, {"Task Force 141": "141特遣队", "Park": "朴"})

    def test_hand_edited_file_is_used_and_bad_file_is_survived(self):
        (self.dir / "game.json").write_text('{"terms": {"Price": "普莱斯上尉"}}', encoding="utf-8")
        self.assertEqual(Glossary("Game", self.dir).terms, {"Price": "普莱斯上尉"})
        (self.dir / "game.json").write_text("not json", encoding="utf-8")
        self.assertEqual(Glossary("Game", self.dir).terms, {})

    def test_store_shares_one_glossary_per_game(self):
        store = GlossaryStore(self.dir)
        self.assertIs(store.get("Game"), store.get("game"))
        self.assertIsNone(store.get(None))


if __name__ == "__main__":
    unittest.main()
