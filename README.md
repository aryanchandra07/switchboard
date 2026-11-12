# Switchboard – LLM Model Evaluation and Intelligent Routing System

**A modular system for evaluating Large Language Models (LLMs), intelligent task-based model routing, and performance monitoring.**

---

## 1. Project Overview

Switchboard is a modular project designed to evaluate multiple locally hosted Large Language Models and intelligently route incoming tasks to suitable models based on task category and difficulty.

The project focuses on model performance evaluation, response quality analysis, processing efficiency, and intelligent model selection.

The repository is organized into separate modules for routing, gateway services, infrastructure, and web application components.

## 2. Problem Statement

Different Large Language Models demonstrate different capabilities across coding, reasoning, general knowledge, and summarization tasks.

Using a single model for every task may result in unnecessary processing time, inefficient resource utilization, and unsuccessful responses.

Switchboard addresses this challenge by providing a systematic framework to evaluate multiple models and select suitable models based on task requirements.

## 3. Project Objectives

- Evaluate multiple locally hosted LLMs.
- Develop a dataset containing 100 evaluation tasks.
- Organize tasks into four categories.
- Implement intelligent task-based model routing.
- Measure response time and token generation speed.
- Evaluate model responses using heuristic quality checks.
- Generate structured JSON and CSV evaluation reports.
- Develop an interactive Streamlit dashboard.
- Analyze routing accuracy and model performance.

## 4. Key Features

- **Multi-Model Evaluation:** Compare four locally hosted language models.
- **Intelligent Routing:** Select models according to task category and difficulty.
- **Performance Benchmarking:** Measure response time and token generation speed.
- **Quality Evaluation:** Apply category-specific heuristic checks.
- **Result Management:** Store evaluation results in JSON and CSV formats.
- **Interactive Dashboard:** Visualize model performance using Streamlit and Plotly.
- **Routing Analysis:** Measure the accuracy of model selection.

## 5. Repository Structure

```text
switchboard/
│
├── docs/
│   └── Project documentation
│
├── gateway/
│   └── Gateway services
│
├── infra/
│   └── Infrastructure configuration
│
├── router/
│   ├── data/
│   │   └── sample_data.py
│   ├── benchmark.py
│   ├── compare_models.py
│   ├── dashboard.py
│   ├── evaluator.py
│   ├── metrics.py
│   ├── router.py
│   ├── requirements.txt
│   └── README.md
│
├── web/
│   └── Web application
│
└── README.md
```

## 6. Technologies Used

| Technology | Purpose |
|---|---|
| Python | Core programming |
| Ollama | Local LLM execution |
| Streamlit | Interactive dashboard |
| Pandas | Data processing |
| NumPy | Numerical operations |
| Plotly | Data visualization |
| Requests | API communication |
| Pytest | Testing |

## 7. System Architecture

The system follows a modular architecture consisting of task input, task classification, intelligent model routing, local model execution, response evaluation, metrics calculation, result storage, and dashboard visualization.

The architecture diagram will be added in this section.

## 8. LLM Models Evaluated

The project evaluates four locally hosted models:

| Model | Primary Focus |
|---|---|
| Llama 3.2:3b | General-purpose tasks |
| Qwen 2.5 Coder:3b | Coding tasks |
| Qwen 2.5:3b | General-purpose tasks |
| DeepSeek R1:1.5b | Reasoning tasks |

## 9. Evaluation Categories

The evaluation dataset contains 100 tasks divided equally into four categories.

| Category | Number of Tasks |
|---|---:|
| Coding | 25 |
| Reasoning | 25 |
| General Knowledge | 25 |
| Summarization | 25 |
| **Total** | **100** |

Each category includes easy, medium, and hard difficulty levels.

## 10. Performance Metrics

The system evaluates model performance using the following metrics:

- **Response Time:** Time required to generate a response.
- **Token Generation Speed:** Number of generated tokens per second.
- **Request Success Rate:** Percentage of successfully completed requests.
- **Heuristic Quality Score:** Score based on predefined response evaluation checks.
- **Routing Accuracy:** Percentage of tasks assigned to the expected model.

*Note: Heuristic quality scores do not represent verified factual correctness or human evaluation.*

## 11. Experimental Results

The 100-task evaluation produced the following results:

| Metric | Result |
|---|---:|
| Total Evaluation Tasks | 100 |
| Successful Requests | 91 |
| Incomplete Requests | 3 |
| Failed Requests | 6 |
| Request Success Rate | 91% |
| Average Response Time (successful tasks) | 40.90 seconds |
| Average Token Speed (successful tasks) | 14.06 tokens/second |
| Average Heuristic Quality (successful tasks) | 98.08/100 |

### Routing Evaluation

| Metric | Result |
|---|---:|
| Total Routing Decisions | 100 |
| Correct Model Selections | 100 |
| Incorrect Model Selections | 0 |
| Routing Accuracy | 100% |

*Routing accuracy measures correct model selection, while request success rate measures successful response generation. These are separate metrics.*

## 12. Interactive Dashboard

The project includes a Streamlit dashboard that provides:

- Overall model benchmarking.
- Model performance comparison.
- Average response time analysis.
- Model success rate comparison.
- Token generation speed visualization.
- Category-wise evaluation results.
- Heuristic quality analysis.
- Routing accuracy analysis.
- CSV result downloads.

## 13. Installation and Setup

### Prerequisites

- Python 3.10 or later.
- Ollama installed on your system.
- Git.
- Required local language models downloaded through Ollama.

### Step 1: Clone the repository

```bash
git clone https://github.com/aryanchandra07/switchboard.git
```

### Step 2: Navigate to the router module

```bash
cd switchboard/router
```

### Step 3: Create a virtual environment

```bash
python -m venv .venv
```

### Step 4: Activate the environment

Windows PowerShell:

```powershell
.venv\Scripts\Activate.ps1
```

### Step 5: Install dependencies

```bash
pip install -r requirements.txt
```

### Step 6: Start Ollama

Make sure Ollama is running and the required models are downloaded.

### Step 7: Run the dashboard

```bash
streamlit run dashboard.py
```

Open the local URL displayed in your terminal to access the dashboard.

## 14. Future Enhancements

- Support additional locally hosted language models.
- Improve intelligent routing strategies.
- Expand the evaluation dataset.
- Introduce human-based response evaluation.
- Add resource utilization monitoring.
- Improve dashboard visualizations.
- Explore deployment options for wider accessibility.

## 15. Conclusion

Switchboard demonstrates a modular approach to evaluating locally hosted Large Language Models and selecting suitable models according to task requirements.

The 100-task evaluation achieved a 91% request success rate, while the routing benchmark achieved 100% correct model selections.

By combining model benchmarking, intelligent routing, heuristic evaluation, and interactive visualization, the project provides a foundation for further research and development in efficient LLM systems.

## 16. Authors

1. **Shaik Ahamed Ali**
2. **Aryan Chandra**
3. **Chaitanya Narkedamillly**

**Programme:** M.E. Software Systems  
**University:** BITS Pilani, Dubai Campus

**Project:** Switchboard – LLM Model Evaluation and Intelligent Routing System

**Technologies:** Python | Ollama | Streamlit | Pandas | NumPy | Plotly

---

**Project:** Switchboard – LLM Model Evaluation and Intelligent Routing System

**Technologies:** Python | Ollama | Streamlit | Pandas | NumPy | Plotly
