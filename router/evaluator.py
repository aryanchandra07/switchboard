import argparse
import ast
import csv
import json
import re
import time
from pathlib import Path

import requests

from router import route_task
from data.sample_data import evaluation_data


# ============================================================
# CONFIGURATION
# ============================================================

OLLAMA_URL = "http://localhost:11434/api/generate"

BASE_DIR = Path(__file__).parent
OUTPUT_FILE = BASE_DIR / "evaluation_results.json"
CSV_FILE = BASE_DIR / "evaluation_results.csv"

REQUEST_TIMEOUT = 300
MAX_RETRIES = 2
RETRY_DELAY = 5
MAX_OUTPUT_TOKENS = 1536

CATEGORY_EVALUATION_TYPES = {
    "coding": "code_quality",
    "reasoning": "reasoning_quality",
    "general": "factual_quality",
    "summarization": "summary_quality",
}


# ============================================================
# GENERAL RESPONSE CHECKS
# ============================================================

def extract_code_blocks(response):
    """Extract fenced code blocks from a model response."""
    pattern = r"```(?:python|py)?\s*(.*?)```"

    return [
        block.strip()
        for block in re.findall(
            pattern,
            response,
            re.DOTALL | re.IGNORECASE
        )
        if block.strip()
    ]


def has_incomplete_language(text):
    """Detect common signs that a response ended prematurely."""

    lower_text = text.lower()

    incomplete_phrases = [
        "please provide more information",
        "could you provide more details",
        "i need more information",
        "please provide the algorithm",
        "your question is unclear",
        "let me know if you want me to continue",
        "to be continued",
    ]

    return any(
        phrase in lower_text
        for phrase in incomplete_phrases
    )


def evaluate_code_quality(response):
    """Heuristic checks for Python code presence and syntax."""

    issues = []

    code_blocks = extract_code_blocks(response)

    if code_blocks:
        code_to_check = code_blocks

    else:
        patterns = [
            r"\bdef\s+\w+\s*\(",
            r"\bclass\s+\w+",
            r"\bimport\s+\w+",
            r"\bfrom\s+\w+\s+import\b",
            r"\bprint\s*\(",
            r"\bfor\s+\w+\s+in\s+",
        ]

        if any(
            re.search(pattern, response)
            for pattern in patterns
        ):
            code_to_check = [response.strip()]

        else:
            return 70, [
                "No recognizable Python code example found"
            ]

    valid_blocks = 0
    invalid_blocks = 0

    for code in code_to_check:

        try:
            ast.parse(code)
            valid_blocks += 1

        except SyntaxError:
            invalid_blocks += 1

    if invalid_blocks:
        issues.append(
            f"{invalid_blocks} code block(s) contain invalid Python syntax"
        )

    if valid_blocks == 0:
        return 55, issues or [
            "No syntactically valid Python code found"
        ]

    return (
        100 if invalid_blocks == 0 else 65
    ), issues


def evaluate_reasoning_quality(response):
    """Check visible reasoning structure, not mathematical truth."""

    lower_text = response.lower()
    words = response.split()

    issues = []
    score = 100

    reasoning_terms = [
        "because",
        "therefore",
        "since",
        "thus",
        "so",
        "step",
        "first",
        "next",
        "finally",
        "result",
    ]

    if not any(
        term in lower_text
        for term in reasoning_terms
    ):
        score -= 15
        issues.append(
            "Limited visible reasoning or justification"
        )

    if len(words) < 30:
        score -= 20
        issues.append(
            "Reasoning explanation is very brief"
        )

    if not any(
        term in lower_text
        for term in [
            "answer",
            "result",
            "therefore",
            "thus",
        ]
    ):
        score -= 10
        issues.append(
            "Conclusion or final result is not clearly signposted"
        )

    return max(0, score), issues


def evaluate_factual_quality(response):
    """Basic structure and relevance heuristics; does not verify facts."""

    words = response.split()

    issues = []
    score = 100

    if len(words) < 20:
        score -= 20
        issues.append(
            "Technical explanation is very brief"
        )

    if not re.search(r"[.!?]", response):
        score -= 10
        issues.append(
            "Response has no clear sentence punctuation"
        )

    if (
        len(words) >= 20
        and len(set(word.lower() for word in words)) < 8
    ):
        score -= 15
        issues.append(
            "Response contains limited vocabulary or repeated wording"
        )

    return max(0, score), issues


def evaluate_summary_quality(response):
    """Check summary length and sentence structure heuristically."""

    issues = []
    score = 100

    words = response.split()

    sentences = [
        sentence
        for sentence in re.split(
            r"(?<=[.!?])\s+",
            response.strip()
        )
        if sentence.strip()
    ]

    if len(words) < 10:
        score -= 30
        issues.append(
            "Summary is too short"
        )

    if len(sentences) < 2:
        score -= 15
        issues.append(
            "Summary contains fewer than two detectable sentences"
        )

    elif len(sentences) > 5:
        score -= 10
        issues.append(
            "Summary is longer than the requested three-sentence format"
        )

    return max(0, score), issues


# ============================================================
# CATEGORY-AWARE RESPONSE QUALITY EVALUATION
# ============================================================

def evaluate_response_quality(task, response):
    """
    Score response characteristics using category-specific heuristics.

    These checks do not establish factual correctness,
    algorithmic correctness, or semantic similarity
    to a reference answer.
    """

    if not response or not response.strip():

        return {
            "quality_score": 0,
            "quality_status": "poor",
            "quality_issues": ["Empty response"],
            "evaluation_type": task.get(
                "evaluation_type",
                "heuristic"
            ),
            "evaluation_method": task.get(
                "evaluation_method",
                "heuristic"
            ),
            "evaluation_criteria": task.get(
                "evaluation_criteria",
                []
            ),
        }

    category = str(
        task.get("category", "")
    ).lower()

    evaluation_type = task.get(
        "evaluation_type",
        CATEGORY_EVALUATION_TYPES.get(
            category,
            "heuristic"
        ),
    )

    evaluation_method = task.get(
        "evaluation_method",
        "heuristic"
    )

    criteria = task.get(
        "evaluation_criteria",
        []
    )

    text = response.strip()
    words = text.split()

    score = 100
    issues = []

    # Common response checks

    if has_incomplete_language(text):
        score -= 15
        issues.append(
            "Response may be incomplete or requesting clarification"
        )

    if len(words) < 10:
        score -= 20
        issues.append(
            "Response is too short"
        )

    elif len(words) < 20 and category != "summarization":
        score -= 10
        issues.append(
            "Limited explanation"
        )

    # Category-specific evaluation

    if category == "coding":

        category_score, category_issues = (
            evaluate_code_quality(text)
        )

    elif category == "reasoning":

        category_score, category_issues = (
            evaluate_reasoning_quality(text)
        )

    elif category == "general":

        category_score, category_issues = (
            evaluate_factual_quality(text)
        )

    elif category == "summarization":

        category_score, category_issues = (
            evaluate_summary_quality(text)
        )

    else:

        category_score = 80
        category_issues = [
            "Unknown category; used general heuristic"
        ]

    # Combine common and category-specific checks

    score = min(score, category_score)

    issues.extend(category_issues)

    score = max(
        0,
        min(100, int(round(score)))
    )

    if score >= 80:
        quality_status = "good"

    elif score >= 50:
        quality_status = "average"

    else:
        quality_status = "poor"

    if not issues:
        issues.append(
            "No issues detected by heuristic checks"
        )

    return {
        "quality_score": score,
        "quality_status": quality_status,
        "quality_issues": issues,
        "evaluation_type": evaluation_type,
        "evaluation_method": evaluation_method,
        "evaluation_criteria": criteria,
    }


# ============================================================
# OLLAMA MODEL EVALUATION
# ============================================================

def evaluate_model(task):

    selected_model = route_task(task)

    payload = {
        "model": selected_model,
        "prompt": task["task"],
        "stream": False,
        "options": {
            "temperature": 0,
            "num_predict": MAX_OUTPUT_TOKENS,
        },
    }

    start_time = time.time()
    last_error = None

    for attempt in range(1, MAX_RETRIES + 1):

        try:

            print(
                f"Attempt: {attempt}/{MAX_RETRIES}"
            )

            response = requests.post(
                OLLAMA_URL,
                json=payload,
                timeout=REQUEST_TIMEOUT,
            )

            response.raise_for_status()

            result = response.json()

            elapsed = round(
                time.time() - start_time,
                2
            )

            model_response = result.get(
                "response",
                ""
            ).strip()

            done_reason = result.get(
                "done_reason"
            )

            if not model_response:
                raise ValueError(
                    "Ollama returned an empty response"
                )

            quality = evaluate_response_quality(
                task,
                model_response
            )

            status = "success"

            if (
                result.get("done") is False
                or done_reason == "length"
            ):
                status = "incomplete"

            eval_count = result.get(
                "eval_count"
            ) or 0

            eval_duration = result.get(
                "eval_duration"
            ) or 0

            tokens_per_second = (
                round(
                    eval_count /
                    (eval_duration / 1_000_000_000),
                    2
                )
                if eval_duration > 0
                else 0
            )

            return {
                "task_id": task["id"],
                "task": task["task"],
                "category": task["category"],
                "difficulty": task["difficulty"],
                "expected_model": task.get(
                    "expected_model"
                ),
                "model": selected_model,
                "response": model_response,
                "response_time": elapsed,
                "status": status,
                "done_reason": done_reason,
                "generated_tokens": eval_count,
                "prompt_tokens": result.get(
                    "prompt_eval_count"
                ) or 0,
                "tokens_per_second": tokens_per_second,
                **quality,
            }

        except (
            requests.exceptions.RequestException,
            ValueError,
            KeyError,
        ) as error:

            last_error = str(error)

            print(
                "Evaluation error:",
                last_error
            )

            if attempt < MAX_RETRIES:

                print(
                    f"Retrying in {RETRY_DELAY} seconds..."
                )

                time.sleep(RETRY_DELAY)

    elapsed = round(
        time.time() - start_time,
        2
    )

    return {
        "task_id": task["id"],
        "task": task["task"],
        "category": task["category"],
        "difficulty": task["difficulty"],
        "expected_model": task.get(
            "expected_model"
        ),
        "model": selected_model,
        "response": "",
        "response_time": elapsed,
        "status": "failed",
        "error": last_error,
        "done_reason": None,
        "generated_tokens": 0,
        "prompt_tokens": 0,
        "tokens_per_second": 0,
        "quality_score": 0,
        "quality_status": "poor",
        "quality_issues": [
            "Evaluation failed"
        ],
        "evaluation_type": task.get(
            "evaluation_type",
            "heuristic"
        ),
        "evaluation_method": task.get(
            "evaluation_method",
            "heuristic"
        ),
        "evaluation_criteria": task.get(
            "evaluation_criteria",
            []
        ),
    }


# ============================================================
# SAVE AND LOAD RESULTS
# ============================================================

def save_results(results):
    """Save JSON and CSV results."""

    with open(
        OUTPUT_FILE,
        "w",
        encoding="utf-8"
    ) as file:

        json.dump(
            results,
            file,
            indent=4,
            ensure_ascii=False
        )

    if results:

        csv_results = []

        for result in results:

            row = result.copy()

            for field in (
                "quality_issues",
                "evaluation_criteria"
            ):

                value = row.get(field, [])

                row[field] = (
                    "; ".join(map(str, value))
                    if isinstance(value, list)
                    else value
                )

            csv_results.append(row)

        # Keep a consistent union of fields across every row.

        fieldnames = list(dict.fromkeys(
            key
            for row in csv_results
            for key in row.keys()
        ))

        with open(
            CSV_FILE,
            "w",
            newline="",
            encoding="utf-8-sig"
        ) as file:

            writer = csv.DictWriter(
                file,
                fieldnames=fieldnames,
                extrasaction="ignore"
            )

            writer.writeheader()
            writer.writerows(csv_results)


def load_results():

    if not OUTPUT_FILE.exists():
        return []

    try:

        with open(
            OUTPUT_FILE,
            "r",
            encoding="utf-8"
        ) as file:

            data = json.load(file)

        return data if isinstance(data, list) else []

    except (
        json.JSONDecodeError,
        OSError
    ):

        print(
            "Warning: Previous results could not be loaded."
        )

        return []


# ============================================================
# EVALUATION SUMMARY
# ============================================================

def print_summary(results):

    total = len(results)

    successful = [
        r for r in results
        if r.get("status") == "success"
    ]

    incomplete = [
        r for r in results
        if r.get("status") == "incomplete"
    ]

    failed = [
        r for r in results
        if r.get("status") == "failed"
    ]

    print(
        "\n========== EVALUATION SUMMARY =========="
    )

    print("Total Tasks:", total)
    print("Successful:", len(successful))
    print("Incomplete:", len(incomplete))
    print("Failed:", len(failed))

    if total:

        print(
            "Success Rate:",
            round(
                len(successful) / total * 100,
                2
            ),
            "%"
        )

    scored = [
        r for r in results
        if r.get("status") in (
            "success",
            "incomplete"
        )
    ]

    if scored:

        avg_quality = sum(
            r.get("quality_score", 0)
            for r in scored
        ) / len(scored)

        avg_time = sum(
            r.get("response_time", 0)
            for r in scored
        ) / len(scored)

        avg_speed = sum(
            r.get("tokens_per_second", 0)
            for r in scored
        ) / len(scored)

        print(
            "Average Quality Score:",
            round(avg_quality, 2)
        )

        print(
            "Average Response Time:",
            round(avg_time, 2),
            "seconds"
        )

        print(
            "Average Token Speed:",
            round(avg_speed, 2),
            "tokens/second"
        )

        distribution = {
            status: sum(
                r.get("quality_status") == status
                for r in scored
            )
            for status in (
                "good",
                "average",
                "poor"
            )
        }

        print("\nQuality Distribution:")

        for status, count in distribution.items():

            print(
                f"{status.title()}: {count}"
            )

    print("\nCategory-wise Evaluation:")

    categories = sorted({
        r.get("category", "unknown")
        for r in results
    })

    for category in categories:

        category_results = [
            r for r in results
            if r.get("category") == category
        ]

        category_scored = [
            r for r in category_results
            if r.get("status") in (
                "success",
                "incomplete"
            )
        ]

        avg = (
            sum(
                r.get("quality_score", 0)
                for r in category_scored
            ) / len(category_scored)
            if category_scored
            else 0
        )

        print(
            f"{category}: {len(category_results)} tasks, "
            f"average heuristic score {avg:.2f}"
        )

    print(
        "========================================"
    )


# ============================================================
# MAIN EVALUATION
# ============================================================

def run_evaluation(limit=None, resume=False):

    results = load_results() if resume else []

    completed_ids = {
        str(result.get("task_id"))
        for result in results
        if result.get("status") in (
            "success",
            "incomplete"
        )
    }

    tasks = (
        evaluation_data[:limit]
        if limit is not None
        else evaluation_data
    )

    print(
        "\n========== AI MODEL EVALUATION ==========\n"
    )

    print(
        "Tasks in this run:",
        len(tasks)
    )

    print(
        "Evaluation method: heuristic"
    )

    for index, task in enumerate(
        tasks,
        start=1
    ):

        task_id = str(task["id"])

        if resume and task_id in completed_ids:

            print(
                f"Skipping completed Task {task_id}"
            )

            continue

        print(
            f"\nEvaluating Task {task_id} "
            f"({index}/{len(tasks)})"
        )

        print(
            "Category:",
            task["category"]
        )

        print(
            "Difficulty:",
            task["difficulty"]
        )

        print(
            "Evaluation type:",
            task.get(
                "evaluation_type",
                "heuristic"
            )
        )

        result = evaluate_model(task)

        # Replace any older record for this task ID.

        results = [
            item for item in results
            if str(item.get("task_id")) != task_id
        ]

        results.append(result)

        results.sort(
            key=lambda item: int(
                item.get("task_id", 0)
            )
        )

        # Save after every task to protect progress.

        save_results(results)

        print(
            "Selected Model:",
            result["model"]
        )

        print(
            "Status:",
            result["status"]
        )

        if result["status"] != "failed":

            print(
                "Response Time:",
                result["response_time"],
                "seconds"
            )

            print(
                "Quality Score:",
                result["quality_score"]
            )

            print(
                "Quality Status:",
                result["quality_status"]
            )

            print(
                "Generated Tokens:",
                result.get("generated_tokens", 0)
            )

            print(
                "Quality Issues:",
                result.get("quality_issues", [])
            )

        else:

            print(
                "Error:",
                result.get("error")
            )

        print("-" * 55)

    print("\nEvaluation completed!")

    print(
        "JSON Results:",
        OUTPUT_FILE
    )

    print(
        "CSV Results:",
        CSV_FILE
    )

    print_summary(results)


# ============================================================
# COMMAND LINE
# ============================================================

if __name__ == "__main__":

    parser = argparse.ArgumentParser(
        description="Category-aware LLM response quality evaluation"
    )

    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Number of tasks to evaluate"
    )

    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume using previously saved results"
    )

    args = parser.parse_args()

    if args.limit is not None and args.limit < 1:

        parser.error(
            "--limit must be at least 1"
        )

    run_evaluation(
        limit=args.limit,
        resume=args.resume
    )