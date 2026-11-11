"""
LLM Model Benchmarking System
Supports comparative benchmarking and intelligent routing.
Uses Ollama for local LLM inference.
"""

import argparse
import csv
import json
import time
from pathlib import Path

import requests

from data.sample_data import evaluation_data
from router import route_task


# =====================================================
# CONFIGURATION
# =====================================================

BASE_DIR = Path(__file__).resolve().parent

OLLAMA_URL = "http://localhost:11434"
GENERATE_URL = f"{OLLAMA_URL}/api/generate"
TAGS_URL = f"{OLLAMA_URL}/api/tags"

MODELS = [
    "llama3.2:3b",
    "qwen2.5-coder:3b",
    "qwen2.5:3b",
    "deepseek-r1:1.5b",
]

DEFAULT_LIMIT = 3
REQUEST_TIMEOUT = 600
MAX_RETRIES = 2
RETRY_DELAY = 5
MAX_OUTPUT_TOKENS = 2048

CSV_FIELDS = [
    "model",
    "task_id",
    "task",
    "category",
    "difficulty",
    "response",
    "response_time",
    "status",
    "error",
    "done_reason",
    "generated_tokens",
    "prompt_tokens",
    "tokens_per_second",
    "selected_model",
    "expected_model",
    "routing_match",
    "evaluation_type",
    "evaluation_method",
]


# =====================================================
# HELPER FUNCTIONS
# =====================================================

def safe_int(value):
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def safe_float(value):
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def model_is_installed(model, installed_models):
    if model in installed_models:
        return True

    if ":" not in model:
        return f"{model}:latest" in installed_models

    return False


# =====================================================
# CHECK OLLAMA CONNECTION
# =====================================================

def check_ollama():

    print("\nChecking Ollama connection...")

    try:
        response = requests.get(TAGS_URL, timeout=15)
        response.raise_for_status()

        installed_models = [
            item["name"]
            for item in response.json().get("models", [])
        ]

        print("\nOllama connection successful!")

        print("\nInstalled models:")

        for model in installed_models:
            print("-", model)

        missing = [
            model for model in MODELS
            if not model_is_installed(model, installed_models)
        ]

        if missing:
            print("\nMissing configured models:")

            for model in missing:
                print("-", model)

        return installed_models

    except (requests.RequestException, ValueError) as error:

        print("\nCannot connect to Ollama:", error)
        print("Please make sure Ollama is running.")

        return None


# =====================================================
# TEST INDIVIDUAL MODEL
# =====================================================

def test_model(model, task):

    prompt = str(task.get("task", "")).strip()

    result = {
        "model": model,
        "task_id": task.get("id"),
        "task": prompt,
        "category": task.get("category", "general"),
        "difficulty": task.get("difficulty", "medium"),
        "response": "",
        "response_time": 0,
        "status": "failed",
        "error": None,
        "done_reason": None,
        "generated_tokens": 0,
        "prompt_tokens": 0,
        "tokens_per_second": 0,
        "selected_model": None,
        "expected_model": task.get("expected_model"),
        "routing_match": None,
        "evaluation_type": task.get("evaluation_type"),
        "evaluation_method": task.get("evaluation_method"),
    }

    if not prompt:
        result["error"] = "Empty task prompt."
        return result

    for attempt in range(1, MAX_RETRIES + 1):

        print(f"\nAttempt {attempt}/{MAX_RETRIES}")
        print("Model:", model)

        start = time.perf_counter()

        try:

            response = requests.post(
                GENERATE_URL,
                json={
                    "model": model,
                    "prompt": prompt,
                    "stream": False,
                    "options": {
                        "num_predict": MAX_OUTPUT_TOKENS,
                        "temperature": 0,
                    },
                },
                timeout=REQUEST_TIMEOUT,
            )

            response.raise_for_status()

            data = response.json()

            elapsed = round(
                time.perf_counter() - start, 2
            )

            answer = data.get("response", "")
            generated = safe_int(data.get("eval_count"))
            prompt_tokens = safe_int(
                data.get("prompt_eval_count")
            )

            done_reason = data.get("done_reason")
            eval_duration = safe_int(
                data.get("eval_duration")
            )

            speed = (
                round(
                    generated / (eval_duration / 1_000_000_000),
                    2
                )
                if eval_duration > 0
                else 0
            )

            result.update({
                "response": answer,
                "response_time": elapsed,
                "done_reason": done_reason,
                "generated_tokens": generated,
                "prompt_tokens": prompt_tokens,
                "tokens_per_second": speed,
                "error": None,
            })

            if not answer.strip():

                result["status"] = "failed"
                result["error"] = "Empty model response."

            elif data.get("done") is not True:

                result["status"] = "incomplete"
                result["error"] = "Generation not completed."

            elif done_reason == "length":

                result["status"] = "incomplete"
                result["error"] = (
                    "Output token limit reached."
                )

            else:

                result["status"] = "success"

            print("Status:", result["status"])
            print("Response time:", elapsed, "seconds")
            print("Generated tokens:", generated)
            print("Tokens per second:", speed)

            if result["status"] == "success":
                return result

        except requests.Timeout:

            elapsed = round(
                time.perf_counter() - start, 2
            )

            result.update({
                "response_time": elapsed,
                "status": "failed",
                "error": "Request timed out.",
            })

            print("Request timed out.")

        except (requests.RequestException, ValueError) as error:

            elapsed = round(
                time.perf_counter() - start, 2
            )

            result.update({
                "response_time": elapsed,
                "status": "failed",
                "error": str(error),
            })

            print("Request failed:", error)

        if attempt < MAX_RETRIES:
            print("Retrying in", RETRY_DELAY, "seconds...")
            time.sleep(RETRY_DELAY)

    return result


# =====================================================
# SAVE RESULTS
# =====================================================

def save_results(results, filename):

    json_path = BASE_DIR / filename
    csv_path = json_path.with_suffix(".csv")

    temp_path = json_path.with_suffix(".json.tmp")

    with open(temp_path, "w", encoding="utf-8") as file:

        json.dump(
            results,
            file,
            indent=4,
            ensure_ascii=False,
        )

    temp_path.replace(json_path)

    with open(
        csv_path,
        "w",
        newline="",
        encoding="utf-8-sig",
    ) as file:

        writer = csv.DictWriter(
            file,
            fieldnames=CSV_FIELDS,
            extrasaction="ignore",
        )

        writer.writeheader()
        writer.writerows(results)

    print("\nResults saved:", json_path.name)
    print("Total records:", len(results))


# =====================================================
# LOAD PREVIOUS RESULTS
# =====================================================

def load_results(filename, resume=False):

    path = BASE_DIR / filename

    if not resume or not path.exists():
        return []

    try:

        with open(path, "r", encoding="utf-8") as file:
            results = json.load(file)

        if not isinstance(results, list):
            raise ValueError("Results file must contain a list.")

        print("Loaded previous records:", len(results))

        return results

    except (OSError, json.JSONDecodeError, ValueError) as error:

        raise RuntimeError(
            f"Cannot resume results: {error}"
        )


def upsert_result(results, new_result):

    key = (
        str(new_result.get("task_id")),
        new_result.get("model"),
    )

    results = [
        item for item in results
        if (
            str(item.get("task_id")),
            item.get("model"),
        ) != key
    ]

    results.append(new_result)

    return results


# =====================================================
# SELECT TASKS
# =====================================================

def select_tasks(limit):

    if limit < 0:
        raise ValueError("Limit cannot be negative.")

    if limit == 0:
        return list(evaluation_data)

    return list(evaluation_data[:limit])


# =====================================================
# COMPARATIVE BENCHMARK
# =====================================================

def run_benchmark(limit=DEFAULT_LIMIT, resume=False):

    filename = "benchmark_results.json"

    tasks = select_tasks(limit)

    results = load_results(filename, resume)

    completed = {
        (
            str(item.get("task_id")),
            item.get("model"),
        )
        for item in results
        if item.get("status") == "success"
    }

    print("\n======================================")
    print("COMPARATIVE MODEL BENCHMARK")
    print("======================================")

    print("Models:", len(MODELS))
    print("Tasks:", len(tasks))
    print("Total planned tests:", len(MODELS) * len(tasks))

    for model_index, model in enumerate(MODELS, start=1):

        print(f"\nModel {model_index}/{len(MODELS)}: {model}")

        for task_index, task in enumerate(tasks, start=1):

            key = (
                str(task.get("id")),
                model,
            )

            if key in completed:

                print(
                    "Skipping completed task:",
                    task.get("id"),
                )

                continue

            print(
                f"\nTask {task_index}/{len(tasks)}"
            )

            print("Task ID:", task.get("id"))
            print("Category:", task.get("category"))
            print("Difficulty:", task.get("difficulty"))

            result = test_model(model, task)

            results = upsert_result(results, result)

            # Save after every test.
            save_results(results, filename)

            if result["status"] == "success":
                completed.add(key)

    print_summary(results, tasks, models=MODELS)


# =====================================================
# INTELLIGENT ROUTING BENCHMARK
# =====================================================

def run_routing_benchmark(limit=DEFAULT_LIMIT, resume=False):

    filename = "routing_benchmark_results.json"

    tasks = select_tasks(limit)

    results = load_results(filename, resume)

    completed_ids = {
        str(item.get("task_id"))
        for item in results
        if item.get("status") == "success"
    }

    print("\n======================================")
    print("INTELLIGENT ROUTING BENCHMARK")
    print("======================================")

    print("Total tasks:", len(tasks))

    for index, task in enumerate(tasks, start=1):

        task_id = str(task.get("id"))

        if task_id in completed_ids:

            print("Skipping completed task:", task_id)
            continue

        print(f"\nTask {index}/{len(tasks)}")
        print("Category:", task.get("category"))
        print("Difficulty:", task.get("difficulty"))

        try:

            selected_model = route_task(task)

        except Exception as error:

            print("Router error:", error)

            result = {
                "model": None,
                "task_id": task.get("id"),
                "task": task.get("task", ""),
                "category": task.get("category", "general"),
                "difficulty": task.get("difficulty", "medium"),
                "response": "",
                "response_time": 0,
                "status": "failed",
                "error": str(error),
                "done_reason": None,
                "generated_tokens": 0,
                "prompt_tokens": 0,
                "tokens_per_second": 0,
                "selected_model": None,
                "expected_model": task.get("expected_model"),
                "routing_match": False,
                "evaluation_type": task.get("evaluation_type"),
                "evaluation_method": task.get("evaluation_method"),
            }

        else:

            result = test_model(selected_model, task)

            expected_model = task.get("expected_model")

            result["selected_model"] = selected_model
            result["expected_model"] = expected_model

            result["routing_match"] = (
                selected_model == expected_model
                if expected_model is not None
                else None
            )

            print("Selected model:", selected_model)
            print("Expected model:", expected_model)
            print("Routing match:", result["routing_match"])

        results = [
            item for item in results
            if str(item.get("task_id")) != task_id
        ]

        results.append(result)

        save_results(results, filename)

        if result["status"] == "success":
            completed_ids.add(task_id)

    print_summary(results, tasks, routing=True)


# =====================================================
# PERFORMANCE SUMMARY
# =====================================================

def print_summary(results, tasks, models=None, routing=False):

    task_ids = {
        str(task.get("id"))
        for task in tasks
    }

    selected_results = [
        item for item in results
        if str(item.get("task_id")) in task_ids
        and (
            routing
            or models is None
            or item.get("model") in models
        )
    ]

    successful = [
        item for item in selected_results
        if item.get("status") == "success"
    ]

    incomplete = [
        item for item in selected_results
        if item.get("status") == "incomplete"
    ]

    failed = [
        item for item in selected_results
        if item.get("status") == "failed"
    ]

    total = len(selected_results)

    success_rate = (
        len(successful) / total * 100
        if total else 0
    )

    print("\n======================================")
    print("BENCHMARK SUMMARY")
    print("======================================")

    print("Recorded tests:", total)
    print("Successful:", len(successful))
    print("Incomplete:", len(incomplete))
    print("Failed:", len(failed))
    print("Success rate:", round(success_rate, 2), "%")

    if successful:

        average_time = sum(
            safe_float(item.get("response_time"))
            for item in successful
        ) / len(successful)

        average_speed = sum(
            safe_float(item.get("tokens_per_second"))
            for item in successful
        ) / len(successful)

        print("Average response time:", round(average_time, 2))
        print("Average token speed:", round(average_speed, 2))

    if routing:

        labeled = [
            item for item in selected_results
            if item.get("routing_match") is not None
        ]

        correct = sum(
            item.get("routing_match") is True
            for item in labeled
        )

        accuracy = (
            correct / len(labeled) * 100
            if labeled else 0
        )

        print("Labeled routing tests:", len(labeled))
        print("Correct routes:", correct)
        print("Routing accuracy:", round(accuracy, 2), "%")

    if models and not routing:

        print("\nMODEL-WISE PERFORMANCE")

        for model in models:

            model_results = [
                item for item in selected_results
                if item.get("model") == model
            ]

            model_success = [
                item for item in model_results
                if item.get("status") == "success"
            ]

            model_incomplete = sum(
                item.get("status") == "incomplete"
                for item in model_results
            )

            model_failed = sum(
                item.get("status") == "failed"
                for item in model_results
            )

            avg_time = (
                sum(
                    safe_float(item.get("response_time"))
                    for item in model_success
                ) / len(model_success)
                if model_success else 0
            )

            avg_speed = (
                sum(
                    safe_float(item.get("tokens_per_second"))
                    for item in model_success
                ) / len(model_success)
                if model_success else 0
            )

            print("\nModel:", model)
            print("Successful:", len(model_success))
            print("Incomplete:", model_incomplete)
            print("Failed:", model_failed)
            print("Average time:", round(avg_time, 2), "seconds")
            print("Average speed:", round(avg_speed, 2), "tokens/sec")

    print("\nBenchmark completed.")
    print("Note: Completion rate does not measure answer correctness.")


# =====================================================
# MAIN PROGRAM
# =====================================================

def main():

    parser = argparse.ArgumentParser(
        description="LLM Model Benchmarking System"
    )

    parser.add_argument(
        "--mode",
        choices=["compare", "routing"],
        default="compare",
    )

    parser.add_argument(
        "--limit",
        type=int,
        default=DEFAULT_LIMIT,
        help="Use 0 to run all dataset tasks.",
    )

    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume successful tests from previous results.",
    )

    args = parser.parse_args()

    if args.limit < 0:
        parser.error("--limit cannot be negative.")

    installed_models = check_ollama()

    if installed_models is None:
        raise SystemExit(1)

    missing = [
        model for model in MODELS
        if not model_is_installed(model, installed_models)
    ]

    if args.mode == "compare":

        if missing:

            print("\nMissing models. Install them first:")

            for model in missing:
                print("ollama pull", model)

            raise SystemExit(1)

        run_benchmark(
            limit=args.limit,
            resume=args.resume,
        )

    else:

        if missing:

            print("\nWarning: Some configured models are missing.")

            for model in missing:
                print("-", model)

        run_routing_benchmark(
            limit=args.limit,
            resume=args.resume,
        )


if __name__ == "__main__":
    main()