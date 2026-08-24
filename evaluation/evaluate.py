#!/usr/bin/env python3
"""Optional, offline evaluator for labelled /analyze exports.

Required columns: gender_label, gender_prediction, gender_confidence.
Optional age columns: age_bracket_label, age_bracket_prediction, age_bracket_confidence.
"""
import argparse
import collections
import csv


def accuracy(rows, label, prediction):
    return sum(row[label] == row[prediction] for row in rows) / len(rows) if rows else 0.0


def report_task(rows, label, prediction, confidence, name):
    if not rows:
        return
    labels = sorted({row[label] for row in rows} | {row[prediction] for row in rows})
    matrix = collections.Counter((row[label], row[prediction]) for row in rows)
    print(f"{name}_accuracy={accuracy(rows, label, prediction):.3f}")
    print(f"{name}_confusion_matrix={dict(matrix)}")
    print(f"{name}_average_confidence={sum(float(row[confidence]) for row in rows) / len(rows):.3f}")
    for value in labels:
        tp = matrix[value, value]
        fp = sum(matrix[actual, value] for actual in labels if actual != value)
        fn = sum(matrix[value, predicted] for predicted in labels if predicted != value)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        print(f"{name} class={value} precision={precision:.3f} recall={recall:.3f} f1={f1:.3f}")


parser = argparse.ArgumentParser(description="Summarise labelled CSV results exported from /analyze")
parser.add_argument("csv", help="CSV results file")
args = parser.parse_args()
with open(args.csv, newline="", encoding="utf-8") as source:
    rows = list(csv.DictReader(source))
if not rows:
    print("samples=0")
elif not {"gender_label", "gender_prediction", "gender_confidence"}.issubset(rows[0]):
    raise SystemExit("CSV must include gender_label, gender_prediction, gender_confidence")
else:
    print(f"samples={len(rows)}")
    report_task(rows, "gender_label", "gender_prediction", "gender_confidence", "gender")
    age_columns = {"age_bracket_label", "age_bracket_prediction", "age_bracket_confidence"}
    if age_columns.issubset(rows[0]):
        report_task(rows, "age_bracket_label", "age_bracket_prediction", "age_bracket_confidence", "age_bracket")
