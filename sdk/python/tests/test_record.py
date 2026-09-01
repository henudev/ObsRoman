"""SDK 单元测试补充说明：本文件确保 record() 自动生成 trace_id。"""
import unittest

from trace_log_sdk import record


class RecordHelperTest(unittest.TestCase):
    def test_trace_id_auto_generated(self):
        entry = record("order-service", "m")
        self.assertRegex(entry["trace_id"], r"^[0-9a-f]{32}$")
        self.assertEqual(entry["level"], "INFO")
        self.assertIn("timestamp", entry)

    def test_explicit_trace_id_kept(self):
        entry = record("order-service", "m", trace_id="a" * 32)
        self.assertEqual(entry["trace_id"], "a" * 32)


if __name__ == "__main__":
    unittest.main()
