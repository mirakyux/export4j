# SPDX-License-Identifier: Apache-2.0
"""Independently reconcile concurrent recovery counters, process samples and all final PDF rows."""
import hashlib
import json
from pathlib import Path
import re
from pypdf import PdfReader
from verify_advanced_pdf import require

ROOT = Path(__file__).resolve().parents[1]


def main():
    directory = Path((ROOT / "build/verification/streaming-stability-directory.txt").read_text().strip())
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8-sig"))
    expected_hash = hashlib.sha256((ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes()).hexdigest()
    cases = {case["mode"]: case for case in manifest["cases"]}
    require(set(cases) == {"discard", "pdf"}, "missing stability process mode")
    pages = 0
    summary = {}
    for mode, case in cases.items():
        workload = directory / mode
        report = json.loads((workload / "report.json").read_text())
        workers, rounds, rows = (manifest[key] for key in ("workers", "rounds", "rows"))
        require(report["mode"] == mode and report["workers"] == workers and report["rounds"] == rounds
                and report["rows"] == rows and report["fontSha256"] == expected_hash, "stability runtime differs from requested workload")
        require(case["exitCode"] == 0 and case["exitTemporaryFiles"] == 0 and case["samples"] > 0, "stability process failed or left resources")
        samples = json.loads((workload / "process-samples.json").read_text(encoding="utf-8-sig"))
        if isinstance(samples, dict): samples = [samples]
        require(len(samples) == case["samples"], "process sample count differs")
        for field, peak in (("workingSetBytes", "peakWorkingSetBytes"), ("privateBytes", "peakPrivateBytes"),
                            ("handles", "peakHandles"), ("temporaryBytes", "peakTemporaryBytes")):
            require(max(sample[field] for sample in samples) == case[peak], "process peak does not match observations")
        records = report["records"]
        require(len(records) == workers * rounds and {(r["worker"], r["round"]) for r in records}
                == {(w, r) for w in range(workers) for r in range(rounds)}, "missing or duplicate worker/round")
        for record in records:
            worker, round_number = record["worker"], record["round"]
            failure = ["READ", "CLOSE", "ACCEPT", "CANCEL", "OUTPUT"][round_number % 5]
            require(record["failure"] == failure and record["failureCloses"] == record["sourceCloses"] == 1
                    and record["rows"] == rows and record["sourceNextCalls"] == rows + 1, "source lifecycle or recovery differs")
            if failure == "READ": require(record["failureNextCalls"] == 5, "read failure consumed extra source")
            elif failure in {"CLOSE", "OUTPUT"}: require(record["failureNextCalls"] == rows + 1, "end-of-output failure source differs")
            else: require(1 < record["failureNextCalls"] < rows, "page failure did not stop source before EOF")
            output = workload / f"worker-{worker}" / f"round-{round_number}.{'pdf' if mode == 'pdf' else 'bin'}"
            require(output.stat().st_size == record["outputBytes"], "recovery output size differs")
            if mode == "pdf":
                reader = PdfReader(output, strict=True)
                require(len(reader.pages) == record["pages"], "recovery PDF page count differs")
                text = "\n".join(page.extract_text() for page in reader.pages)
                expected = [f"W{worker}-R{round_number}-ROW-{value:05}" for value in range(1, rows + 1)]
                require(re.findall(r"W\d+-R\d+-ROW-\d{5}", text) == expected, "concurrent PDF rows cross-contaminated, repeated or lost")
                require(all(page.extract_text().count("ROW-ID") == 1 for page in reader.pages), "recovery header repetition differs")
                pages += len(reader.pages)
            else: require(output.read_bytes() == bytes([1, 2]), "discard sink recovery output differs")
        require(not list((workload / "tmp").rglob("*.*")) and not list(workload.rglob(".export4j-*")), "temporary files remain after exit")
        summary[mode] = {"cycles": len(records), "recoveredRows": rows * len(records), "elapsedMilliseconds": report["elapsedMilliseconds"],
                         "peakObservedHeapBytes": max(record["observedHeapBytes"] for record in records), **case}
    result = {"workers": manifest["workers"], "rounds": manifest["rounds"], "rows": manifest["rows"],
              "pdfPages": pages, "cases": summary, "claim": "finite workload; no constant-memory or long-term leak claim"}
    (directory / "independent-report.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
