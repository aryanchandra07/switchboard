
import csv
import json
from pathlib import Path
from collections import defaultdict
from statistics import mean


# ============================================================
# CONFIGURATION
# ============================================================

BASE_DIR = Path(__file__).parent

RESULTS_FILE = BASE_DIR / "evaluation_results.json"
JSON_OUTPUT = BASE_DIR / "metrics_summary.json"
CSV_OUTPUT = BASE_DIR / "metrics_summary.csv"


# ============================================================
# HELPER FUNCTIONS
# ============================================================

def average(values):
    valid_values = [
        value for value in values
        if isinstance(value, (int, float))
    ]

    return round(mean(valid_values), 2) if valid_values else 0


def percentage(part, total):
    return round((part / total) * 100, 2) if total else 0


def get_valid_values(results, field):
    return [
        item[field]
        for item in results
        if isinstance(item.get(field), (int, float))
    ]


# ============================================================
# LOAD RESULTS
# ============================================================

def load_results():

    if not RESULTS_FILE.exists():
        print("Evaluation results file not found:", RESULTS_FILE)
        return None

    try:
        with open(RESULTS_FILE, "r", encoding="utf-8") as file:
            results = json.load(file)

        if not isinstance(results, list):
            print("Invalid results format. Expected a JSON list.")
            return None

        return results

    except (json.JSONDecodeError, OSError) as error:
        print("Error loading evaluation results:", error)
        return None


# ============================================================
# OVERALL METRICS
# ============================================================

def calculate_overall_metrics(results):

    total = len(results)

    successful = [
        item for item in results
        if item.get("status") == "success"
    ]

    incomplete = [
        item for item in results
        if item.get("status") == "incomplete"
    ]

    failed = [
        item for item in results
        if item.get("status") == "failed"
    ]

    response_times = get_valid_values(successful, "response_time")
    quality_scores = get_valid_values(successful, "quality_score")
    token_speeds = get_valid_values(successful, "tokens_per_second")

    return {
        "total_tasks": total,
        "successful_tasks": len(successful),
        "incomplete_tasks": len(incomplete),
        "failed_tasks": len(failed),

        "success_rate": percentage(len(successful), total),
        "incomplete_rate": percentage(len(incomplete), total),
        "failure_rate": percentage(len(failed), total),

        "average_response_time": average(response_times),
        "fastest_response_time": round(min(response_times), 2)
        if response_times else 0,
        "slowest_response_time": round(max(response_times), 2)
        if response_times else 0,

        "average_quality_score": average(quality_scores),
        "average_tokens_per_second": average(token_speeds)
    }


# ============================================================
# CATEGORY-WISE METRICS
# ============================================================

def calculate_category_metrics(results):

    category_data = defaultdict(list)

    for item in results:
        category = item.get("category", "Unknown")
        category_data[category].append(item)

    metrics = {}

    for category, items in category_data.items():

        successful = [
            item for item in items
            if item.get("status") == "success"
        ]

        times = get_valid_values(successful, "response_time")
        scores = get_valid_values(successful, "quality_score")
        speeds = get_valid_values(successful, "tokens_per_second")

        metrics[category] = {
            "total_tasks": len(items),
            "successful_tasks": len(successful),
            "failed_or_incomplete_tasks": len(items) - len(successful),
            "success_rate": percentage(len(successful), len(items)),
            "average_response_time": average(times),
            "average_quality_score": average(scores),
            "average_tokens_per_second": average(speeds)
        }

    return metrics


# ============================================================
# MODEL-WISE METRICS
# ============================================================

def calculate_model_metrics(results):

    model_data = defaultdict(list)

    for item in results:
        model = item.get("model", "Unknown")
        model_data[model].append(item)

    metrics = {}

    for model, items in model_data.items():

        successful = [
            item for item in items
            if item.get("status") == "success"
        ]

        incomplete = [
            item for item in items
            if item.get("status") == "incomplete"
        ]

        failed = [
            item for item in items
            if item.get("status") == "failed"
        ]

        times = get_valid_values(successful, "response_time")
        scores = get_valid_values(successful, "quality_score")
        speeds = get_valid_values(successful, "tokens_per_second")

        good = sum(
            item.get("quality_status") == "good"
            for item in successful
        )

        average_quality = average(scores)

        metrics[model] = {
            "total_tasks": len(items),
            "successful_tasks": len(successful),
            "incomplete_tasks": len(incomplete),
            "failed_tasks": len(failed),

            "success_rate": percentage(len(successful), len(items)),
            "average_response_time": average(times),
            "average_quality_score": average_quality,
            "average_tokens_per_second": average(speeds),

            "good_quality_responses": good,
            "average_quality_responses": sum(
                item.get("quality_status") == "average"
                for item in successful
            ),
            "poor_quality_responses": sum(
                item.get("quality_status") == "poor"
                for item in successful
            )
        }

    return metrics


# ============================================================
# EXPORT METRICS
# ============================================================

def save_metrics(report):

    # Save JSON report
    with open(JSON_OUTPUT, "w", encoding="utf-8") as file:
        json.dump(report, file, indent=4, ensure_ascii=False)

    # Prepare CSV rows
    rows = []

    overall = report["overall"]

    for metric, value in overall.items():
        rows.append({
            "section": "overall",
            "name": metric,
            "value": value
        })

    for category, data in report["categories"].items():
        for metric, value in data.items():
            rows.append({
                "section": "category",
                "name": category,
                "metric": metric,
                "value": value
            })

    for model, data in report["models"].items():
        for metric, value in data.items():
            rows.append({
                "section": "model",
                "name": model,
                "metric": metric,
                "value": value
            })

    with open(
        CSV_OUTPUT,
        "w",
        newline="",
        encoding="utf-8-sig"
    ) as file:

        fieldnames = ["section", "name", "metric", "value"]

        writer = csv.DictWriter(
            file,
            fieldnames=fieldnames
        )

        writer.writeheader()
        writer.writerows(rows)


# ============================================================
# PRINT REPORT
# ============================================================

def print_report(report):

    overall = report["overall"]

    print("\n========== AI MODEL PERFORMANCE REPORT ==========")

    print("\n---------- OVERALL PERFORMANCE ----------")

    print("Total Tasks:", overall["total_tasks"])
    print("Successful Tasks:", overall["successful_tasks"])
    print("Incomplete Tasks:", overall["incomplete_tasks"])
    print("Failed Tasks:", overall["failed_tasks"])

    print("Success Rate:", overall["success_rate"], "%")
    print("Incomplete Rate:", overall["incomplete_rate"], "%")
    print("Failure Rate:", overall["failure_rate"], "%")

    print("\n---------- RESPONSE TIME ----------")

    print(
        "Average:",
        overall["average_response_time"],
        "seconds"
    )

    print(
        "Fastest:",
        overall["fastest_response_time"],
        "seconds"
    )

    print(
        "Slowest:",
        overall["slowest_response_time"],
        "seconds"
    )

    print("\n---------- QUALITY METRICS ----------")

    print(
        "Average Quality Score:",
        overall["average_quality_score"],
        "/ 100"
    )

    print(
        "Average Token Speed:",
        overall["average_tokens_per_second"],
        "tokens/second"
    )

    print("\n---------- CATEGORY PERFORMANCE ----------")

    for category, data in report["categories"].items():

        print(f"\nCategory: {category}")
        print("Total Tasks:", data["total_tasks"])
        print("Success Rate:", data["success_rate"], "%")
        print(
            "Average Response Time:",
            data["average_response_time"],
            "seconds"
        )
        print(
            "Average Quality:",
            data["average_quality_score"]
        )

    print("\n---------- MODEL PERFORMANCE ----------")

    model_averages = {}

    for model, data in report["models"].items():

        print(f"\nModel: {model}")
        print("Total Tasks:", data["total_tasks"])
        print("Successful:", data["successful_tasks"])
        print("Incomplete:", data["incomplete_tasks"])
        print("Failed:", data["failed_tasks"])
        print("Success Rate:", data["success_rate"], "%")

        print(
            "Average Response Time:",
            data["average_response_time"],
            "seconds"
        )

        print(
            "Average Quality Score:",
            data["average_quality_score"]
        )

        print(
            "Average Token Speed:",
            data["average_tokens_per_second"],
            "tokens/second"
        )

        model_averages[model] = data["average_response_time"]

    # Fastest model
    print("\n---------- FASTEST MODEL ----------")

    valid_models = {
        model: data["average_response_time"]
        for model, data in report["models"].items()
        if data["successful_tasks"] > 0
        and data["average_response_time"] > 0
    }

    if valid_models:

        fastest_model = min(valid_models, key=valid_models.get)

        print("Model:", fastest_model)
        print("Average Time:", valid_models[fastest_model], "seconds")

    else:
        print("No successful response times available.")

    print("\n==================================================")


# ============================================================
# MAIN FUNCTION
# ============================================================

def calculate_metrics():

    results = load_results()

    if results is None or not results:
        print("No evaluation results available.")
        return

    overall = calculate_overall_metrics(results)
    categories = calculate_category_metrics(results)
    models = calculate_model_metrics(results)

    report = {
        "overall": overall,
        "categories": categories,
        "models": models
    }

    print_report(report)

    save_metrics(report)

    print("\nMetrics successfully generated!")
    print("JSON Report:", JSON_OUTPUT)
    print("CSV Report:", CSV_OUTPUT)


if __name__ == "__main__":
    calculate_metrics()
