
import csv
import json
from pathlib import Path
from collections import defaultdict
from statistics import mean


# ============================================================
# CONFIGURATION
# ============================================================

BASE_DIR = Path(__file__).parent

BENCHMARK_FILE = BASE_DIR / "benchmark_results.json"
EVALUATION_FILE = BASE_DIR / "evaluation_results.json"
ROUTING_FILE = BASE_DIR / "routing_benchmark_results.json"

JSON_OUTPUT = BASE_DIR / "model_comparison_report.json"
CSV_OUTPUT = BASE_DIR / "model_comparison_report.csv"


# ============================================================
# HELPER FUNCTIONS
# ============================================================

def load_json(path):

    if not path.exists():
        print(f"File not found: {path}")
        return None

    try:
        with open(path, "r", encoding="utf-8") as file:
            data = json.load(file)

        if not isinstance(data, list):
            print(f"Invalid data format in {path.name}. Expected a list.")
            return None

        return data

    except (json.JSONDecodeError, OSError) as error:
        print(f"Error reading {path.name}: {error}")
        return None


def average(values):

    valid = [
        value for value in values
        if isinstance(value, (int, float))
    ]

    return round(mean(valid), 2) if valid else None


def percentage(part, total):

    return round(part / total * 100, 2) if total else 0


# ============================================================
# BENCHMARK MODEL METRICS
# ============================================================

def calculate_model_metrics(results):

    model_data = defaultdict(list)

    for item in results:

        model = item.get("model", "Unknown")
        model_data[model].append(item)

    report = {}

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

        response_times = [
            item.get("response_time")
            for item in successful
            if isinstance(item.get("response_time"), (int, float))
        ]

        token_speeds = [
            item.get("tokens_per_second")
            for item in successful
            if isinstance(item.get("tokens_per_second"), (int, float))
        ]

        generated_tokens = [
            item.get("generated_tokens")
            for item in successful
            if isinstance(item.get("generated_tokens"), (int, float))
        ]

        report[model] = {
            "total_tests": len(items),
            "successful": len(successful),
            "incomplete": len(incomplete),
            "failed": len(failed),

            "success_rate": percentage(
                len(successful), len(items)
            ),

            "incomplete_rate": percentage(
                len(incomplete), len(items)
            ),

            "failure_rate": percentage(
                len(failed), len(items)
            ),

            "average_response_time": average(response_times),
            "fastest_response_time": (
                round(min(response_times), 2)
                if response_times else None
            ),
            "slowest_response_time": (
                round(max(response_times), 2)
                if response_times else None
            ),

            "average_tokens_per_second": average(token_speeds),
            "average_generated_tokens": average(generated_tokens)
        }

    return report


# ============================================================
# CATEGORY-WISE COMPARISON
# ============================================================

def calculate_category_metrics(results):

    category_data = defaultdict(lambda: defaultdict(list))

    for item in results:

        model = item.get("model", "Unknown")
        category = item.get("category", "Unknown")

        category_data[category][model].append(item)

    report = {}

    for category, models in category_data.items():

        report[category] = {}

        for model, items in models.items():

            successful = [
                item for item in items
                if item.get("status") == "success"
            ]

            times = [
                item.get("response_time")
                for item in successful
                if isinstance(item.get("response_time"), (int, float))
            ]

            report[category][model] = {
                "total_tests": len(items),
                "successful": len(successful),
                "success_rate": percentage(
                    len(successful), len(items)
                ),
                "average_response_time": average(times)
            }

    return report


# ============================================================
# QUALITY METRICS
# ============================================================

def calculate_quality_metrics(results):

    model_data = defaultdict(list)

    for item in results:

        if item.get("status") != "success":
            continue

        model = item.get("model", "Unknown")
        model_data[model].append(item)

    report = {}

    for model, items in model_data.items():

        scores = [
            item.get("quality_score")
            for item in items
            if isinstance(item.get("quality_score"), (int, float))
        ]

        report[model] = {
            "evaluated_tasks": len(items),
            "average_quality_score": average(scores),
            "good": sum(
                item.get("quality_status") == "good"
                for item in items
            ),
            "average": sum(
                item.get("quality_status") == "average"
                for item in items
            ),
            "poor": sum(
                item.get("quality_status") == "poor"
                for item in items
            ),
            "source": "Router-selected evaluation results"
        }

    return report


# ============================================================
# ROUTING METRICS
# ============================================================

def calculate_routing_metrics(results):

    if not results:
        return {
            "total_tasks": 0,
            "correct_routes": 0,
            "incorrect_routes": 0,
            "routing_accuracy": None
        }

    labeled = [
        item for item in results
        if isinstance(item.get("routing_match"), bool)
    ]

    correct = sum(
        item.get("routing_match") is True
        for item in labeled
    )

    incorrect = sum(
        item.get("routing_match") is False
        for item in labeled
    )

    return {
        "total_tasks": len(results),
        "labeled_tasks": len(labeled),
        "correct_routes": correct,
        "incorrect_routes": incorrect,
        "routing_accuracy": percentage(
            correct, len(labeled)
        ) if labeled else None
    }


# ============================================================
# RANKING
# ============================================================

def calculate_rankings(model_metrics):

    rankings = []

    for model, data in model_metrics.items():

        rankings.append({
            "model": model,
            "success_rate": data["success_rate"],
            "average_response_time": data["average_response_time"],
            "average_tokens_per_second": data[
                "average_tokens_per_second"
            ]
        })

    # Rank using success rate first, then response time.
    # Token speed is used as the final tie-breaker.
    rankings.sort(
        key=lambda item: (
            item["success_rate"],
            -(
                item["average_response_time"]
                if item["average_response_time"] is not None
                else float("inf")
            ),
            item["average_tokens_per_second"]
            if item["average_tokens_per_second"] is not None
            else -1
        ),
        reverse=True
    )

    for position, item in enumerate(rankings, start=1):
        item["rank"] = position

    return rankings


# ============================================================
# SAVE REPORTS
# ============================================================

def save_reports(report):

    with open(JSON_OUTPUT, "w", encoding="utf-8") as file:
        json.dump(report, file, indent=4, ensure_ascii=False)

    rows = []

    def add_rows(section, data):

        for name, values in data.items():

            if isinstance(values, dict):

                for metric, value in values.items():

                    if isinstance(value, (dict, list)):
                        continue

                    rows.append({
                        "section": section,
                        "name": name,
                        "metric": metric,
                        "value": value
                    })

            else:

                rows.append({
                    "section": section,
                    "name": name,
                    "metric": "",
                    "value": values
                })

    add_rows("model", report["models"])
    add_rows("category", report["categories"])
    add_rows("quality", report["quality"])
    add_rows("routing", report["routing"])

    for item in report["rankings"]:

        for metric, value in item.items():

            rows.append({
                "section": "ranking",
                "name": item["model"],
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

    print("\n========== AI MODEL COMPARISON REPORT ==========")

    print("\n---------- MODEL PERFORMANCE ----------")

    for model, data in report["models"].items():

        print(f"\nModel: {model}")
        print("Total Tests:", data["total_tests"])
        print("Successful:", data["successful"])
        print("Incomplete:", data["incomplete"])
        print("Failed:", data["failed"])
        print("Success Rate:", data["success_rate"], "%")

        print(
            "Average Response Time:",
            data["average_response_time"],
            "seconds"
        )

        print(
            "Average Token Speed:",
            data["average_tokens_per_second"],
            "tokens/second"
        )

        print(
            "Average Generated Tokens:",
            data["average_generated_tokens"]
        )

    print("\n---------- CATEGORY PERFORMANCE ----------")

    for category, models in report["categories"].items():

        print(f"\nCategory: {category}")

        for model, data in models.items():

            print(
                f"{model}: "
                f"Success Rate = {data['success_rate']}%, "
                f"Average Time = {data['average_response_time']} seconds"
            )

    print("\n---------- QUALITY EVALUATION ----------")

    if report["quality"]:

        print(
            "Note: These scores come from router-selected tasks, "
            "not an equal four-model comparison."
        )

        for model, data in report["quality"].items():

            print(f"\nModel: {model}")
            print("Evaluated Tasks:", data["evaluated_tasks"])
            print("Average Quality:", data["average_quality_score"])
            print("Good:", data["good"])
            print("Average:", data["average"])
            print("Poor:", data["poor"])

    else:
        print("No quality evaluation results available.")

    print("\n---------- MODEL RANKINGS ----------")

    if report["rankings"]:

        for item in report["rankings"]:

            print(
                f"{item['rank']}. {item['model']} "
                f"(Success Rate: {item['success_rate']}%)"
            )

    else:
        print("No model rankings available.")

    print("\n---------- FASTEST MODEL ----------")

    valid_models = [
        (model, data["average_response_time"])
        for model, data in report["models"].items()
        if data["average_response_time"] is not None
    ]

    if valid_models:

        fastest = min(valid_models, key=lambda item: item[1])

        print("Model:", fastest[0])
        print("Average Time:", fastest[1], "seconds")

    else:
        print("No response-time data available.")

    print("\n---------- ROUTING PERFORMANCE ----------")

    routing = report["routing"]

    print("Total Routing Tasks:", routing["total_tasks"])
    print("Labeled Tasks:", routing["labeled_tasks"])
    print("Correct Routes:", routing["correct_routes"])
    print("Incorrect Routes:", routing["incorrect_routes"])

    if routing["routing_accuracy"] is not None:
        print("Routing Accuracy:", routing["routing_accuracy"], "%")
    else:
        print("Routing Accuracy: Not available")

    print("\n================================================")


# ============================================================
# MAIN FUNCTION
# ============================================================

def compare_models():

    benchmark_results = load_json(BENCHMARK_FILE)

    if not benchmark_results:
        print(
            "\nNo comparative benchmark results available."
            "\nRun: python benchmark.py --mode compare"
        )
        return

    evaluation_results = load_json(EVALUATION_FILE)
    routing_results = load_json(ROUTING_FILE)

    model_metrics = calculate_model_metrics(benchmark_results)

    category_metrics = calculate_category_metrics(benchmark_results)

    quality_metrics = calculate_quality_metrics(
        evaluation_results or []
    )

    routing_metrics = calculate_routing_metrics(
        routing_results or []
    )

    rankings = calculate_rankings(model_metrics)

    report = {
        "models": model_metrics,
        "categories": category_metrics,
        "quality": quality_metrics,
        "routing": routing_metrics,
        "rankings": rankings
    }

    print_report(report)

    save_reports(report)

    print("\nComparison reports successfully generated!")
    print("JSON Report:", JSON_OUTPUT)
    print("CSV Report:", CSV_OUTPUT)


if __name__ == "__main__":
    compare_models()
