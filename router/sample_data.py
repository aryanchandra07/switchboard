
"""
AI Model Router Evaluation Dataset

Total: 100 tasks (25 per category)

Categories:
1. Coding
2. Reasoning
3. General
4. Summarization

Difficulty:
Easy: 8 tasks per category
Medium: 9 tasks per category
Hard: 8 tasks per category

Includes:
- Expected model mapping
- Evaluation types
- Evaluation criteria
- Dataset validation
"""

from collections import Counter


# ==================================================
# SAMPLE TASK DATASET
# ==================================================

task_bank = {

    "coding": [
        "Explain Python variables with an example.",
        "Write a Python program to check whether a number is prime.",
        "Write a Python program to reverse a string.",
        "Explain Python lists, tuples, sets and dictionaries.",
        "Write a function to calculate the factorial of a number.",
        "Explain the difference between == and is in Python.",
        "Write a Python program to find the largest element in a list.",
        "Explain Python loops with examples.",
        "Write a program to remove duplicate elements from a list.",
        "Implement binary search in Python.",
        "Explain object-oriented programming in Python.",
        "Create a Python class for managing student records.",
        "Write a program to count word frequencies in a sentence.",
        "Explain exception handling with an example.",
        "Implement bubble sort in Python.",
        "Write a Python program to merge two sorted lists.",
        "Explain decorators and generators in Python.",
        "Implement a stack using Python lists.",
        "Write a program to detect a palindrome string.",
        "Implement a linked list in Python.",
        "Explain time and space complexity with examples.",
        "Write a Python program to find the longest common subsequence.",
        "Implement a basic multithreaded Python application.",
        "Design a Python caching system using a dictionary.",
        "Implement a graph traversal algorithm using BFS and DFS."
    ],

    "reasoning": [
        "If five machines produce five products in five minutes, how long will 100 machines take to produce 100 products?",
        "Analyze the time complexity of binary search.",
        "A train travels 120 km in two hours. Calculate its average speed.",
        "Explain the logical reasoning behind the water jug problem.",
        "If all roses are flowers and some flowers fade quickly, can we conclude all roses fade quickly?",
        "Analyze the space complexity of merge sort.",
        "A person walks 5 km north and 3 km east. Calculate the shortest distance from the starting point.",
        "Explain how recursion works using the Tower of Hanoi problem.",
        "Find the next number in the sequence 2, 6, 12, 20, 30.",
        "Compare greedy algorithms and dynamic programming.",
        "A bag contains three red and two blue balls. Calculate the probability of selecting a red ball.",
        "Explain the optimal strategy for the coin change problem.",
        "Analyze the worst-case complexity of quicksort.",
        "Determine whether a given graph contains a cycle.",
        "Explain the travelling salesperson problem.",
        "Compare depth-first search and breadth-first search.",
        "A farmer has 17 sheep and all but 9 escape. How many remain?",
        "Explain the principle of mathematical induction.",
        "Analyze the complexity of nested loops with different iteration counts.",
        "Find the shortest path in a weighted graph using Dijkstra's algorithm.",
        "Explain the producer-consumer synchronization problem.",
        "Analyze a scheduling problem with multiple competing tasks.",
        "Explain the difference between NP-hard and NP-complete problems.",
        "Design an efficient algorithm to detect duplicate values in a large dataset.",
        "Evaluate the trade-offs between time complexity and memory usage in algorithm design."
    ],

    "general": [
        "Explain the difference between machine learning and deep learning.",
        "What is cloud computing? Explain its main service models.",
        "Explain the working principles of relational databases.",
        "What is an operating system and what are its main functions?",
        "Explain the difference between HTTP and HTTPS.",
        "What is an API? Explain with an example.",
        "Describe the main components of computer networks.",
        "Explain the concept of virtualization.",
        "What is cybersecurity and why is it important?",
        "Explain the difference between SQL and NoSQL databases.",
        "What is data normalization in databases?",
        "Explain the purpose of version control systems.",
        "Describe the software development life cycle.",
        "What are containers and how does Docker work?",
        "Explain the difference between public and private clouds.",
        "What is an IP address and how does DNS work?",
        "Explain the role of a compiler and interpreter.",
        "What is big data? Describe its characteristics.",
        "Explain the concept of data warehousing.",
        "What is an operating system kernel?",
        "Describe the fundamentals of distributed computing.",
        "Explain RESTful web services.",
        "What is database indexing and why is it useful?",
        "Explain the difference between supervised and unsupervised learning.",
        "Describe the importance of software testing."
    ],

    "summarization": [
        "Summarize in three sentences: Artificial intelligence is transforming industries through automation, predictive analytics and intelligent decision-making. Machine learning helps systems identify patterns, while deep learning uses neural networks to solve complex problems.",
        "Summarize in three sentences: Cloud computing provides access to computing resources over the internet. Organizations use cloud services to reduce infrastructure costs, improve scalability and increase flexibility.",
        "Summarize in three sentences: Cybersecurity protects computer systems, networks and sensitive information from unauthorized access. Common security practices include encryption, authentication and regular software updates.",
        "Summarize in three sentences: Data analytics involves collecting, cleaning and analyzing information to discover useful insights. Businesses use these insights to improve operations and make informed decisions.",
        "Summarize in three sentences: Python is a popular programming language known for its simple syntax. It is widely used in automation, web development, artificial intelligence and data science.",
        "Summarize in three sentences: Database management systems organize and store information efficiently. They support data retrieval, modification, security and transaction management.",
        "Summarize in three sentences: Machine learning enables computers to learn from historical data. Models can identify patterns and make predictions without explicitly programming every possible outcome.",
        "Summarize in three sentences: Software engineering applies systematic methods to software development. Requirements analysis, design, implementation, testing and maintenance are important stages.",
        "Summarize in three sentences: Distributed systems consist of multiple computers working together. They provide scalability, resource sharing and improved fault tolerance.",
        "Summarize in three sentences: Blockchain is a distributed digital ledger. Transactions are recorded in connected blocks and protected through cryptographic techniques.",
        "Summarize in three sentences: Internet of Things connects physical devices to the internet. Sensors collect information that can be used for monitoring, automation and analysis.",
        "Summarize in three sentences: Big data refers to extremely large and complex datasets. Specialized technologies help organizations process and analyze this information.",
        "Summarize in three sentences: Data visualization represents information through charts and graphs. It helps people understand patterns, trends and relationships.",
        "Summarize in three sentences: Natural language processing allows computers to understand and generate human language. Applications include translation, chatbots and sentiment analysis.",
        "Summarize in three sentences: DevOps combines software development and IT operations. Automation and continuous integration improve software delivery.",
        "Summarize in three sentences: Cloud storage allows users to save information on remote servers. It supports accessibility, collaboration and scalable storage.",
        "Summarize in three sentences: Computer networks connect devices to exchange information. Communication protocols ensure reliable data transmission.",
        "Summarize in three sentences: Operating systems manage hardware and software resources. They provide interfaces between applications and computer hardware.",
        "Summarize in three sentences: SQL is used to manage relational databases. It supports querying, inserting, updating and deleting records.",
        "Summarize in three sentences: Predictive analytics uses historical data and statistical techniques to estimate future outcomes. Businesses use it for forecasting and planning.",
        "Summarize in three sentences: Artificial neural networks are inspired by the structure of the human brain. They consist of interconnected processing units called neurons.",
        "Summarize in three sentences: Software testing identifies defects and verifies application functionality. Different testing methods help improve reliability and quality.",
        "Summarize in three sentences: Data preprocessing prepares raw information for analysis. It includes handling missing values, removing duplicates and transforming variables.",
        "Summarize in three sentences: Microservices architecture divides applications into smaller independent services. Each service performs a specific function and communicates through APIs.",
        "Summarize in three sentences: Generative AI creates new content such as text, images and code. Large language models use patterns learned from training data to generate responses."
    ]
}


# ==================================================
# MODEL SELECTION MAPPING
# ==================================================

MODEL_MAPPING = {

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


# ==================================================
# EVALUATION CRITERIA
# ==================================================

EVALUATION_CRITERIA = {

    "coding": {
        "evaluation_type": "code_quality",
        "criteria": [
            "Correctness of the proposed solution",
            "Python syntax and code structure",
            "Relevance to the programming task",
            "Explanation of the implementation",
            "Handling of relevant edge cases"
        ]
    },

    "reasoning": {
        "evaluation_type": "reasoning_quality",
        "criteria": [
            "Correctness of the final answer",
            "Logical step-by-step explanation",
            "Use of relevant facts and calculations",
            "Consistency of reasoning",
            "Clarity of the conclusion"
        ]
    },

    "general": {
        "evaluation_type": "factual_quality",
        "criteria": [
            "Factual accuracy",
            "Relevance to the question",
            "Technical correctness",
            "Clarity of explanation",
            "Coverage of important concepts"
        ]
    },

    "summarization": {
        "evaluation_type": "summary_quality",
        "criteria": [
            "Preservation of the original meaning",
            "Coverage of key information",
            "Conciseness",
            "Relevance to the supplied text",
            "Avoidance of unsupported information"
        ]
    }
}


# ==================================================
# GENERATE EVALUATION DATASET
# ==================================================

evaluation_data = []

task_id = 1

for category, tasks in task_bank.items():

    for index, task in enumerate(tasks):

        # Assign difficulty levels
        if index < 8:
            difficulty = "easy"

        elif index < 17:
            difficulty = "medium"

        else:
            difficulty = "hard"

        # Expected model based on routing policy
        expected_model = MODEL_MAPPING[category][difficulty]

        # Category-specific evaluation configuration
        evaluation_info = EVALUATION_CRITERIA[category]

        evaluation_data.append({

            "id": task_id,

            "task": task,

            "category": category,

            "difficulty": difficulty,

            "expected_model": expected_model,

            "evaluation_type": evaluation_info["evaluation_type"],

            "evaluation_criteria": evaluation_info["criteria"],

            "evaluation_method": "heuristic"

        })

        task_id += 1


# ==================================================
# DATASET VALIDATION
# ==================================================

def validate_dataset():

    expected_categories = [
        "coding",
        "reasoning",
        "general",
        "summarization"
    ]

    expected_difficulties = [
        "easy",
        "medium",
        "hard"
    ]

    # 1. Validate total task count
    assert len(evaluation_data) == 100, (
        f"Expected 100 tasks, found {len(evaluation_data)}"
    )

    # 2. Validate unique task IDs
    task_ids = [item["id"] for item in evaluation_data]

    assert len(set(task_ids)) == 100, (
        "Duplicate task IDs found!"
    )

    # 3. Validate continuous task IDs
    assert sorted(task_ids) == list(range(1, 101)), (
        "Task IDs must be continuous from 1 to 100"
    )

    # 4. Validate categories
    assert set(task_bank.keys()) == set(expected_categories), (
        "Unexpected or missing categories"
    )

    for category in expected_categories:

        count = sum(
            1 for item in evaluation_data
            if item["category"] == category
        )

        assert count == 25, (
            f"{category} should contain 25 tasks, found {count}"
        )

    # 5. Validate difficulty distribution
    expected_distribution = {
        "easy": 8,
        "medium": 9,
        "hard": 8
    }

    for category in expected_categories:

        category_tasks = [
            item for item in evaluation_data
            if item["category"] == category
        ]

        for difficulty in expected_difficulties:

            count = sum(
                1 for item in category_tasks
                if item["difficulty"] == difficulty
            )

            expected_count = expected_distribution[difficulty]

            assert count == expected_count, (
                f"{category} {difficulty}: "
                f"expected {expected_count}, found {count}"
            )

    # 6. Validate expected model mapping
    for item in evaluation_data:

        category = item["category"]
        difficulty = item["difficulty"]

        expected = MODEL_MAPPING[category][difficulty]

        assert item["expected_model"] == expected, (
            f"Incorrect model mapping for task {item['id']}"
        )

    # 7. Validate evaluation metadata
    for item in evaluation_data:

        assert item.get("evaluation_type"), (
            f"Missing evaluation type for task {item['id']}"
        )

        assert item.get("evaluation_criteria"), (
            f"Missing evaluation criteria for task {item['id']}"
        )

        assert item.get("evaluation_method") == "heuristic", (
            f"Invalid evaluation method for task {item['id']}"
        )

    # 8. Validate evaluation type mapping
    for item in evaluation_data:

        expected_type = EVALUATION_CRITERIA[
            item["category"]
        ]["evaluation_type"]

        assert item["evaluation_type"] == expected_type, (
            f"Incorrect evaluation type for task {item['id']}"
        )

    # 9. Validate task text
    for item in evaluation_data:

        assert isinstance(item["task"], str) and item["task"].strip(), (
            f"Invalid task text for task {item['id']}"
        )

    return True


# ==================================================
# DISPLAY DATASET INFORMATION
# ==================================================

def display_dataset_information():

    print("\n" + "=" * 60)

    print("       AI MODEL ROUTER EVALUATION DATASET")

    print("=" * 60)

    print(f"\nTotal Tasks: {len(evaluation_data)}")

    # Category distribution
    print("\nCATEGORY-WISE TASK DISTRIBUTION")

    print("-" * 45)

    category_counts = Counter(
        item["category"] for item in evaluation_data
    )

    for category, count in category_counts.items():

        print(f"{category.title():20} : {count}")

    # Difficulty distribution
    print("\nDIFFICULTY DISTRIBUTION")

    print("-" * 45)

    difficulty_counts = Counter(
        item["difficulty"] for item in evaluation_data
    )

    for difficulty in ["easy", "medium", "hard"]:

        print(
            f"{difficulty.title():20} : "
            f"{difficulty_counts[difficulty]}"
        )

    # Model mapping
    print("\nMODEL MAPPING")

    print("-" * 45)

    for category, mapping in MODEL_MAPPING.items():

        print(f"\n{category.title()}")

        for difficulty, model in mapping.items():

            print(f"  {difficulty.title():10} -> {model}")

    # Evaluation types
    print("\nEVALUATION TYPES")

    print("-" * 45)

    for category, info in EVALUATION_CRITERIA.items():

        print(
            f"{category.title():20} : "
            f"{info['evaluation_type']}"
        )

    # Validation
    print("\nDATASET VALIDATION")

    print("-" * 45)

    try:

        validate_dataset()

        print("Total task validation: PASSED")
        print("Unique task IDs: PASSED")
        print("Continuous task IDs: PASSED")
        print("Category distribution: PASSED")
        print("Difficulty distribution: PASSED")
        print("Expected model mapping: PASSED")
        print("Evaluation metadata: PASSED")
        print("Evaluation type mapping: PASSED")
        print("Task text validation: PASSED")

        print("\nALL 100 TASKS VALIDATED SUCCESSFULLY!")

    except AssertionError as error:

        print(f"\nDATASET VALIDATION FAILED: {error}")

        raise

    print("\n" + "=" * 60)


# ==================================================
# MAIN
# ==================================================

if __name__ == "__main__":

    display_dataset_information()
