
import json
import time
import requests
from pathlib import Path

# ==================================================
# CONFIGURATION
# ==================================================

OLLAMA_URL = "http://localhost:11434/api/generate"

MODEL = "deepseek-r1:1.5b"

TIMEOUT = 300
MAX_RETRIES = 2
MAX_OUTPUT_TOKENS = 1536

RESULT_FILE = Path(__file__).parent / "test_result.json"


# ==================================================
# TEST PROMPT
# ==================================================

PROMPT = """
Explain Python functions in detail using simple language.

Include all the following sections:

1. DEFINITION
Explain what a Python function is.

2. SYNTAX
Show the general syntax of a Python function.

3. EXAMPLE
Provide a simple, complete Python function example.

4. EXPLANATION
Explain the example step by step.

5. EXPECTED OUTPUT
Show the exact output produced by the example.

Requirements:
- Use clear and simple language.
- Include valid Python code.
- Make the explanation easy for beginners.
- Complete every section.
- Do not provide an incomplete response.
"""


# ==================================================
# MODEL TEST FUNCTION
# ==================================================

def run_model_test(model, prompt):

    print("\n" + "=" * 60)
    print("OLLAMA MODEL TEST")
    print("=" * 60)

    print(f"Model: {model}")
    print(f"Maximum Output Tokens: {MAX_OUTPUT_TOKENS}")

    last_error = None
    elapsed = 0

    for attempt in range(1, MAX_RETRIES + 1):

        print(f"\nAttempt: {attempt}/{MAX_RETRIES}")

        start_time = time.time()

        try:

            payload = {
                "model": model,
                "prompt": prompt,
                "stream": False,
                "options": {
                    "num_predict": MAX_OUTPUT_TOKENS,
                    "num_ctx": 4096,
                    "temperature": 0.2
                }
            }

            response = requests.post(
                OLLAMA_URL,
                json=payload,
                timeout=TIMEOUT
            )

            response.raise_for_status()

            data = response.json()

            elapsed = time.time() - start_time

            model_response = data.get(
                "response", ""
            ).strip()

            generated_tokens = data.get(
                "eval_count", 0
            )

            generated_characters = len(model_response)

            done_reason = data.get(
                "done_reason", "unknown"
            )

            if not model_response:
                raise ValueError(
                    "Model returned an empty response."
                )

            if done_reason == "length":

                last_error = (
                    "Response reached the output token limit."
                )

                print("\nWARNING:", last_error)

                if attempt < MAX_RETRIES:

                    print(
                        "Retrying with a larger output limit..."
                    )

                    time.sleep(5)
                    continue

            result = {
                "model": model,
                "status": "success",
                "prompt": prompt,
                "response": model_response,
                "response_time": round(elapsed, 2),
                "generated_characters": generated_characters,
                "generated_tokens": generated_tokens,
                "done_reason": done_reason,
                "error": last_error
            }

            print("\n" + "-" * 60)
            print("MODEL RESPONSE")
            print("-" * 60)

            print(model_response)

            print("\n" + "-" * 60)
            print("PERFORMANCE")
            print("-" * 60)

            print(f"Response Time: {elapsed:.2f} seconds")
            print("Status: SUCCESS")

            print(
                f"Generated Characters: {generated_characters}"
            )

            print(
                f"Generated Tokens: {generated_tokens}"
            )

            print(f"Completion Reason: {done_reason}")

            return result

        except requests.exceptions.Timeout:

            elapsed = time.time() - start_time

            last_error = (
                f"Request timed out after {TIMEOUT} seconds."
            )

            print("\nStatus: FAILED")
            print("Error:", last_error)

        except requests.exceptions.RequestException as error:

            elapsed = time.time() - start_time

            last_error = str(error)

            print("\nStatus: FAILED")
            print("Error:", last_error)

        except (ValueError, KeyError) as error:

            elapsed = time.time() - start_time

            last_error = str(error)

            print("\nStatus: FAILED")
            print("Error:", last_error)

        if attempt < MAX_RETRIES:

            print("\nRetrying after 5 seconds...")
            time.sleep(5)

    return {
        "model": model,
        "status": "failed",
        "prompt": prompt,
        "response": "",
        "response_time": round(elapsed, 2),
        "generated_characters": 0,
        "generated_tokens": 0,
        "done_reason": None,
        "error": last_error
    }


# ==================================================
# SAVE RESULTS TO JSON
# ==================================================

def save_results(result):

    with open(
        RESULT_FILE,
        "w",
        encoding="utf-8"
    ) as file:

        json.dump(
            result,
            file,
            indent=4,
            ensure_ascii=False
        )

    print(
        f"\nResults saved successfully: {RESULT_FILE}"
    )


# ==================================================
# PYTEST INTEGRATION TEST
# ==================================================

def test_model():

    result = run_model_test(
        MODEL,
        PROMPT
    )

    assert result["status"] == "success", (
        f"Ollama model test failed: {result.get('error')}"
    )

    assert result["response"], (
        "Model returned an empty response."
    )


# ==================================================
# MAIN EXECUTION
# ==================================================

if __name__ == "__main__":

    result = run_model_test(
        MODEL,
        PROMPT
    )

    print("\n" + "=" * 60)
    print("FINAL TEST SUMMARY")
    print("=" * 60)

    print(
        json.dumps(
            result,
            indent=4,
            ensure_ascii=False
        )
    )

    save_results(result)

    if result["status"] == "success":

        print("\nModel test completed successfully!")

    else:

        print("\nModel test failed. Check the error above.")
