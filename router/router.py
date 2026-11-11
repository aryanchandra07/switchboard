
# Model Router
# Routes tasks based on category and difficulty

MODEL_ROUTING = {
    "coding": {
        "easy": "llama3.2:3b",
        "medium": "qwen2.5-coder:3b",
        "hard": "qwen2.5-coder:3b"
    },
    "reasoning": {
        "easy": "llama3.2:3b",
        "medium": "deepseek-r1:1.5b",
       "hard": "deepseek-r1:1.5b"
    },
    "general": {
        "easy": "llama3.2:3b",
        "medium": "llama3.2:3b",
        "hard": "deepseek-r1:1.5b"
    },
    "summarization": {
        "easy": "llama3.2:3b",
        "medium": "qwen2.5:3b",
        "hard": "qwen2.5:3b"
    }
}


def route_task(task):

    category = task.get("category", "general").lower()
    difficulty = task.get("difficulty", "medium").lower()

    category_models = MODEL_ROUTING.get(
        category,
        MODEL_ROUTING["general"]
    )

    selected_model = category_models.get(
        difficulty,
        "llama3.2:3b"
    )

    return selected_model


if __name__ == "__main__":

    test_tasks = [
        {"category": "coding", "difficulty": "easy"},
        {"category": "coding", "difficulty": "hard"},
        {"category": "reasoning", "difficulty": "hard"},
        {"category": "general", "difficulty": "easy"},
        {"category": "summarization", "difficulty": "medium"}
    ]

    print("\n===== DYNAMIC MODEL ROUTER TEST =====")

    for task in test_tasks:
        model = route_task(task)

        print(
            f"Category: {task['category']} | "
            f"Difficulty: {task['difficulty']} | "
            f"Selected Model: {model}"
        )
