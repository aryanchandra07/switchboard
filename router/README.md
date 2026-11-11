
# Part B – LLM Model Evaluation and Intelligent Routing

## 1. Overview

This module focuses on evaluating multiple locally hosted Large Language Models (LLMs) and implementing an intelligent routing mechanism to select suitable models for incoming tasks.

The system uses Ollama for local model inference and evaluates model performance using response time, success rate, token generation speed and heuristic quality scores.

## 2. Technologies Used

- Python
- Ollama
- Streamlit
- Pandas
- NumPy
- Plotly
- Requests
- Pytest

## 3. Models Evaluated

- Llama 3.2:3b
- Qwen 2.5 Coder:3b
- Qwen 2.5:3b
- DeepSeek R1:1.5b

## 4. Main Features

- Evaluation dataset containing 100 tasks.
- Four task categories: Coding, Reasoning, General and Summarization.
- Task-based intelligent model routing.
- Comparative model benchmarking.
- Response time and token speed measurement.
- Heuristic quality evaluation.
- JSON and CSV result generation.
- Streamlit dashboard for performance visualization.

## 5. Project Structure

```text
router/
│
├── data/
│   └── sample_data.py
│
├── benchmark.py
├── compare_models.py
├── dashboard.py
├── evaluator.py
├── metrics.py
├── router.py
├── test_ollama.py
├── requirements.txt
│
├── benchmark_results.csv
├── benchmark_results.json
├── evaluation_results.csv
├── evaluation_results.json
├── metrics_summary.csv
├── metrics_summary.json
├── model_comparison_report.csv
├── model_comparison_report.json
├── routing_benchmark_results.csv
└── routing_benchmark_results.json
```

## 6. Evaluation Results

The 100-task evaluation recorded:

- 91 successful tasks.
- 3 incomplete tasks.
- 6 failed tasks.
- 91% overall success rate.
- 98.08/100 average heuristic quality among successful tasks.

The routing experiment achieved 100% routing accuracy across the 100 tasks.

Note: Quality scores are based on heuristic evaluation and do not independently establish factual correctness.

## 7. Installation

Install Python dependencies:

```bash
pip install -r requirements.txt
```

Install Ollama and pull the required models:

```bash
ollama pull llama3.2:3b
ollama pull qwen2.5-coder:3b
ollama pull qwen2.5:3b
ollama pull deepseek-r1:1.5b
```

Ensure the Ollama service is running before executing the application.

## 8. Run the Dashboard

```bash
streamlit run dashboard.py
```

## 9. Output

The system generates JSON and CSV files containing benchmark results, evaluation metrics, model comparisons and routing performance.

## 10. Contribution

This folder contains the Part B implementation for LLM benchmarking, intelligent routing, performance evaluation and dashboard visualization within the Switchboard group project.
