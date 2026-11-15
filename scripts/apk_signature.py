"""Read one release certificate from successful apksigner verify output."""
import re


# Build Tools <=36 uses numbered or SDK-range signers; 37 uses scheme names.
SIGNER = (
    r"(?:Signer(?: #\d+| \([^\r\n]*\))"
    r"|V[1-4](?:\.\d+)? Signer(?: #\d+)?(?: \([^\r\n]*\))?:)"
)


def release_certificate(output):
    output = "\n".join(line.strip() for line in output.splitlines())
    certificates = {
        value.lower()
        for value in re.findall(
            r"^" + SIGNER + r" certificate SHA-256 digest: ([a-fA-F0-9]{64})$",
            output,
            re.M,
        )
    }
    counts = re.findall(r"^Number of signers: (\d+)$", output, re.M)
    if len(certificates) != 1 or any(int(count) != 1 for count in counts):
        raise ValueError("APK must have one valid release certificate")
    if re.search(r"^" + SIGNER + r" certificate DN:.*CN=Android Debug(?:,|$)", output, re.M):
        raise ValueError("Debug-signed APK cannot be published")
    return certificates.pop()
