import json
import unittest
from unittest.mock import patch

import httpx

from translator import ContextLine, DeepSeekTranslator


class DeepSeekTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}):
            self.translator = DeepSeekTranslator()
        await self.translator.close()
        self.requests = []
        self.status = 200
        self.payload = {
            "model": "deepseek-flash",
            "choices": [{"finish_reason": "stop", "message": {
                "content": '{"source":"Park: Go!","translation":"Park：快走！"}',
            }}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 20},
        }

        def handle(request):
            self.requests.append(request)
            return httpx.Response(self.status, json=self.payload)

        self.translator._client = httpx.AsyncClient(
            base_url="https://api.deepseek.com",
            headers={"Authorization": "Bearer test-key"},
            transport=httpx.MockTransport(handle),
        )

    async def asyncTearDown(self):
        await self.translator.close()

    async def test_translation_and_request(self):
        result = await self.translator.translate(
            "Park: G0!", [ContextLine("Wait!", "等等！")], "Call of Duty",
        )
        self.assertEqual(result.source, "Park: Go!")
        self.assertEqual(result.translation, "Park：快走！")
        self.assertEqual((result.input_tokens, result.output_tokens), (100, 20))
        request = self.requests[0]
        self.assertEqual(str(request.url), "https://api.deepseek.com/chat/completions")
        self.assertEqual(request.headers["Authorization"], "Bearer test-key")
        body = json.loads(request.content)
        self.assertEqual(body["thinking"], {"type": "disabled"})
        self.assertEqual(body["response_format"], {"type": "json_object"})
        self.assertIn("JSON", body["messages"][0]["content"])
        for text in ("Call of Duty", "Wait!", "等等！", "Park: G0!"):
            self.assertIn(text, body["messages"][1]["content"])

    async def test_empty_and_truncated_answers_are_skipped(self):
        for choices in (
            [],
            [{"finish_reason": "length", "message": {"content": '{"source":'}}],
            [{"finish_reason": "content_filter", "message": {"content": None}}],
            [{"finish_reason": "stop", "message": {"content": ""}}],
        ):
            with self.subTest(choices=choices):
                self.payload["choices"] = choices
                result = await self.translator.translate("Go!", [], None)
                self.assertEqual(result.translation, "")

    async def test_invalid_schema_is_rejected(self):
        for content in ('not json', '[]', '{"source":"Go!"}',
                        '{"source":"Go!","translation":42}'):
            with self.subTest(content=content):
                self.payload["choices"][0]["message"]["content"] = content
                with self.assertRaises(ValueError):
                    await self.translator.translate("Go!", [], None)

    async def test_non_dialogue_is_skipped(self):
        self.payload["choices"][0]["message"]["content"] = '{"source":"","translation":""}'
        result = await self.translator.translate("MENU", [], None)
        self.assertEqual(result.translation, "")

    async def test_api_errors_propagate_to_server_handler(self):
        for status in (401, 429, 500):
            with self.subTest(status=status):
                self.status = status
                with self.assertRaises(httpx.HTTPStatusError):
                    await self.translator.translate("Go!", [], None)

    async def test_missing_key(self):
        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": " "}):
            with self.assertRaisesRegex(ValueError, "DEEPSEEK_API_KEY"):
                DeepSeekTranslator()
