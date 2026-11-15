import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "apk_signature", Path(__file__).resolve().parents[1] / "apk_signature.py"
)
signature = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signature)


class ApkSignatureTest(unittest.TestCase):
    def output(self, label="Signer #1", certificate="a" * 64, dn="CN=SingVerse Release"):
        return (
            f"{label} certificate DN: {dn}\n"
            f"{label} certificate SHA-256 digest: {certificate}\n"
        )

    def test_old_and_build_tools_37_output(self):
        labels = (
            "Signer #1",
            "Signer (minSdkVersion=24, maxSdkVersion=32)",
            "V1 Signer:",
            "V2 Signer:",
            "V3.0 Signer (minSdkVersion=28, maxSdkVersion=32):",
            "V3.1 Signer (minSdkVersion=33, maxSdkVersion=2147483647):",
        )
        for label in labels:
            with self.subTest(label=label):
                self.assertEqual(signature.release_certificate("Number of signers: 1\n" + self.output(label)), "a" * 64)

    def test_repeated_certificate_across_signature_schemes_is_one_identity(self):
        output = "Number of signers: 1\n" + self.output("V1 Signer:") + self.output("V2 Signer:")
        self.assertEqual(signature.release_certificate(output), "a" * 64)

    def test_different_certificates_and_multiple_signers_are_rejected(self):
        for output in (
            self.output("V2 Signer:") + self.output("V3.0 Signer:", "b" * 64),
            "Number of signers: 2\n" + self.output(),
            self.output("Signer #1") + self.output("Signer #2", "b" * 64),
        ):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "one valid release certificate"):
                signature.release_certificate(output)

    def test_debug_certificate_is_rejected_in_both_formats(self):
        for label in ("Signer #1", "V2 Signer:", "V3.0 Signer:"):
            with self.subTest(label=label), self.assertRaisesRegex(ValueError, "Debug-signed"):
                signature.release_certificate(self.output(label, dn="CN=Android Debug, O=Android, C=US"))

    def test_empty_invalid_and_unrecognized_output_are_rejected(self):
        for output in ("", "DOES NOT VERIFY", self.output(certificate="a" * 63), self.output(certificate="z" * 64)):
            with self.subTest(output=output), self.assertRaises(ValueError):
                signature.release_certificate(output)

    def test_source_stamp_and_public_key_digests_are_not_apk_certificates(self):
        output = self.output("V2 Signer:") + self.output("Source Stamp Signer:", "b" * 64)
        output += "V2 Signer: public key SHA-256 digest: " + "c" * 64 + "\n"
        self.assertEqual(signature.release_certificate(output), "a" * 64)

    def test_windows_line_endings_and_uppercase_digests_are_normalized(self):
        output = self.output("V2 Signer:", "AB" * 32).replace("\n", "\r\n")
        self.assertEqual(signature.release_certificate(output), "ab" * 32)


if __name__ == "__main__":
    unittest.main()
