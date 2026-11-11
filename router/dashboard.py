import json
from pathlib import Path

import pandas as pd
import plotly.express as px
import streamlit as st


# ============================================================
# CONFIGURATION
# ============================================================

BASE_DIR = Path(__file__).resolve().parent

BENCHMARK_FILE = BASE_DIR / "benchmark_results.json"
EVALUATION_FILE = BASE_DIR / "evaluation_results.json"
ROUTING_FILE = BASE_DIR / "routing_benchmark_results.json"

st.set_page_config(
    page_title="LLM Model Evaluation Dashboard",
    page_icon="🤖",
    layout="wide",
    initial_sidebar_state="expanded"
)


# ============================================================
# HELPER FUNCTIONS
# ============================================================

def load_json(file_path):
    """Load JSON data safely."""

    if not file_path.exists():
        return None

    try:
        with open(file_path, "r", encoding="utf-8") as file:
            return json.load(file)

    except (json.JSONDecodeError, OSError) as error:
        st.error(f"Error reading {file_path.name}: {error}")
        return None


def to_dataframe(data):
    """Convert JSON list into DataFrame."""

    if isinstance(data, list):
        return pd.DataFrame(data)

    return pd.DataFrame()


def normalize_status(series):
    return (
        series.fillna("failed")
        .astype(str)
        .str.lower()
        .str.strip()
    )


def normalize_boolean(value):
    if isinstance(value, bool):
        return value

    if isinstance(value, (int, float)):
        return value == 1

    if isinstance(value, str):
        return value.strip().lower() in {
            "true", "1", "yes", "correct"
        }

    return False


def numeric_column(df, column, default=None):
    if column not in df.columns:
        df[column] = default

    df[column] = pd.to_numeric(
        df[column],
        errors="coerce"
    )


def show_chart(fig):
    st.plotly_chart(fig, width="stretch")


def csv_download(df, filename, label):
    if df.empty:
        st.info("No records available to download.")
        return

    st.download_button(
        label=label,
        data=df.to_csv(index=False).encode("utf-8"),
        file_name=filename,
        mime="text/csv"
    )


def prepare_results(df):
    """Normalize benchmark and evaluation data."""

    if df.empty:
        return df

    defaults = {
        "task_id": None,
        "model": "Unknown",
        "category": "Unknown",
        "difficulty": "Unknown",
        "status": "failed",
        "response_time": None,
        "generated_tokens": None,
        "tokens_per_second": None,
        "quality_score": None,
        "quality_status": "Not Evaluated"
    }

    for column, default in defaults.items():
        if column not in df.columns:
            df[column] = default

    for column in ["model", "category", "difficulty"]:
        df[column] = (
            df[column].fillna("Unknown").astype(str)
        )

    df["status"] = normalize_status(df["status"])

    for column in [
        "response_time",
        "generated_tokens",
        "tokens_per_second",
        "quality_score"
    ]:
        numeric_column(df, column)

    df["quality_status"] = (
        df["quality_status"]
        .fillna("Not Evaluated")
        .astype(str)
    )

    return df


# ============================================================
# HEADER
# ============================================================

st.title("🤖 LLM Model Evaluation Dashboard")

st.caption(
    "Performance benchmarking, model comparison, response quality "
    "analysis and intelligent routing for locally hosted Ollama models."
)

st.sidebar.title("⚙️ Dashboard Controls")

st.sidebar.markdown(
    """
    **LLM Evaluation System**

    - Model benchmarking
    - Performance comparison
    - Response quality analysis
    - Intelligent model routing
    - Category-wise analysis
    """
)

if st.sidebar.button("🔄 Refresh Dashboard", width="stretch"):
    st.rerun()


# ============================================================
# LOAD DATA
# ============================================================

benchmark_raw = load_json(BENCHMARK_FILE)
evaluation_raw = load_json(EVALUATION_FILE)
routing_raw = load_json(ROUTING_FILE)

benchmark_df = prepare_results(to_dataframe(benchmark_raw))
evaluation_df = prepare_results(to_dataframe(evaluation_raw))
routing_df = to_dataframe(routing_raw)


# ============================================================
# SECTION 1: OVERALL MODEL BENCHMARKING
# ============================================================

st.divider()
st.header("📊 Overall Model Benchmarking")

if benchmark_df.empty:

    st.warning(
        "No comparative benchmark data found. "
        "Run benchmark.py in comparison mode first."
    )

else:

    total_tests = len(benchmark_df)

    successful_benchmark = benchmark_df[
        benchmark_df["status"] == "success"
    ].copy()

    successful = len(successful_benchmark)

    incomplete = int(
        (benchmark_df["status"] == "incomplete").sum()
    )

    failed = int(
        (benchmark_df["status"] == "failed").sum()
    )

    success_rate = (
        successful / total_tests * 100
        if total_tests else 0
    )

    # Calculate performance metrics using successful records only.
    average_time = successful_benchmark["response_time"].mean()
    average_speed = successful_benchmark["tokens_per_second"].mean()

    models_tested = benchmark_df["model"].nunique()

    # Explain that a small comparative benchmark is a pilot.
    if total_tests <= 20:
        st.info(
            f"This comparative benchmark contains {total_tests} pilot tests. "
            "It is separate from the 100-task intelligent-routing evaluation."
        )

    if benchmark_df["category"].nunique() == 1:
        st.caption(
            "Coverage note: this comparative benchmark currently covers "
            f"the {benchmark_df['category'].iloc[0]} category only."
        )

    c1, c2, c3, c4, c5 = st.columns(5)

    c1.metric("Total Tests", total_tests)
    c2.metric("Successful", successful)
    c3.metric("Incomplete", incomplete)
    c4.metric("Failed", failed)
    c5.metric("Success Rate", f"{success_rate:.2f}%")

    c1, c2, c3 = st.columns(3)

    c1.metric(
        "Average Response Time",
        f"{average_time:.2f}s"
        if pd.notna(average_time) else "N/A"
    )

    c2.metric(
        "Average Token Speed",
        f"{average_speed:.2f} tokens/s"
        if pd.notna(average_speed) else "N/A"
    )

    c3.metric("Models Tested", models_tested)

    st.divider()

    # --------------------------------------------------------
    # MODEL-WISE PERFORMANCE
    # --------------------------------------------------------

    st.subheader("⚖️ Model Performance Comparison")

    model_summary = (
        benchmark_df.groupby("model")
        .agg(
            Total_Tests=("status", "count"),
            Successful=(
                "status",
                lambda x: (x == "success").sum()
            ),
            Incomplete=(
                "status",
                lambda x: (x == "incomplete").sum()
            ),
            Failed=(
                "status",
                lambda x: (x == "failed").sum()
            ),
            Average_Response_Time=(
                "response_time",
                lambda x: x[
                    benchmark_df.loc[x.index, "status"] == "success"
                ].mean()
            ),
            Average_Tokens_Per_Second=(
                "tokens_per_second",
                lambda x: x[
                    benchmark_df.loc[x.index, "status"] == "success"
                ].mean()
            )
        )
        .reset_index()
    )

    model_summary["Success_Rate"] = (
        model_summary["Successful"]
        / model_summary["Total_Tests"] * 100
    )

    st.dataframe(
        model_summary.round(2),
        width="stretch",
        hide_index=True
    )

    # --------------------------------------------------------
    # RESPONSE TIME
    # --------------------------------------------------------

    left, right = st.columns(2)

    with left:

        st.subheader("⏱️ Average Response Time")

        time_data = model_summary.dropna(
            subset=["Average_Response_Time"]
        )

        if not time_data.empty:

            fig = px.bar(
                time_data,
                x="model",
                y="Average_Response_Time",
                color="model",
                text_auto=".2f",
                title="Average Response Time by Model",
                labels={
                    "model": "AI Model",
                    "Average_Response_Time": "Seconds"
                }
            )

            show_chart(fig)

    # --------------------------------------------------------
    # SUCCESS RATE
    # --------------------------------------------------------

    with right:

        st.subheader("✅ Model Success Rate")

        fig = px.bar(
            model_summary,
            x="model",
            y="Success_Rate",
            color="model",
            text_auto=".2f",
            range_y=[0, 105],
            title="Success Rate by Model",
            labels={
                "model": "AI Model",
                "Success_Rate": "Success Rate (%)"
            }
        )

        show_chart(fig)

    # --------------------------------------------------------
    # TOKEN GENERATION SPEED
    # --------------------------------------------------------

    st.subheader("⚡ Model Token Generation Speed")

    speed_data = model_summary.dropna(
        subset=["Average_Tokens_Per_Second"]
    )

    if not speed_data.empty:

        fig = px.bar(
            speed_data,
            x="model",
            y="Average_Tokens_Per_Second",
            color="model",
            text_auto=".2f",
            title="Average Tokens Generated per Second",
            labels={
                "model": "AI Model",
                "Average_Tokens_Per_Second": "Tokens/Second"
            }
        )

        show_chart(fig)

    # --------------------------------------------------------
    # CATEGORY-WISE BENCHMARKING
    # --------------------------------------------------------

    st.divider()
    st.subheader("📚 Category-wise Benchmark Performance")

    successful_category_data = benchmark_df[
        benchmark_df["status"] == "success"
    ]

    if not successful_category_data.empty:

        category_summary = (
            successful_category_data.groupby("category")
            .agg(
                Successful_Tasks=("status", "count"),
                Average_Response_Time=("response_time", "mean"),
                Average_Tokens_Per_Second=("tokens_per_second", "mean")
            )
            .reset_index()
        )

        st.dataframe(
            category_summary.round(2),
            width="stretch",
            hide_index=True
        )

        fig = px.bar(
            category_summary,
            x="category",
            y="Average_Response_Time",
            color="category",
            text_auto=".2f",
            title="Average Response Time by Category",
            labels={
                "category": "Task Category",
                "Average_Response_Time": "Response Time (Seconds)"
            }
        )

        show_chart(fig)

    # --------------------------------------------------------
    # DETAILED BENCHMARK RESULTS
    # --------------------------------------------------------

    st.divider()
    st.subheader("📋 Detailed Benchmark Results")

    st.dataframe(
        benchmark_df,
        width="stretch",
        hide_index=True
    )

    csv_download(
        benchmark_df,
        "benchmark_results.csv",
        "⬇️ Download Benchmark Results CSV"
    )


# ============================================================
# SECTION 2: RESPONSE QUALITY ANALYSIS
# ============================================================

st.divider()
st.header("🎯 Response Quality Analysis")

if evaluation_df.empty:

    st.warning(
        "No evaluation results found. "
        "Run evaluator.py to generate evaluation_results.json."
    )

else:

    successful_evaluations = evaluation_df[
        evaluation_df["status"] == "success"
    ].copy()

    incomplete_evaluations = evaluation_df[
        evaluation_df["status"] == "incomplete"
    ]

    failed_evaluations = evaluation_df[
        evaluation_df["status"] == "failed"
    ]

    total_evaluations = len(evaluation_df)
    successful_count = len(successful_evaluations)

    evaluation_success_rate = (
        successful_count / total_evaluations * 100
        if total_evaluations else 0
    )

    quality_data = successful_evaluations.dropna(
        subset=["quality_score"]
    ).copy()

    average_quality = quality_data["quality_score"].mean()

    all_record_quality = evaluation_df["quality_score"].mean()

    average_evaluation_time = (
        successful_evaluations["response_time"].mean()
    )

    average_evaluation_speed = (
        successful_evaluations["tokens_per_second"].mean()
    )

    # --------------------------------------------------------
    # EVALUATION KPI CARDS
    # --------------------------------------------------------

    q1, q2, q3, q4, q5 = st.columns(5)

    q1.metric("Total Evaluations", total_evaluations)
    q2.metric("Successful", successful_count)
    q3.metric("Incomplete", len(incomplete_evaluations))
    q4.metric("Failed", len(failed_evaluations))
    q5.metric(
        "Success Rate",
        f"{evaluation_success_rate:.2f}%"
    )

    st.divider()

    q1, q2, q3 = st.columns(3)

    q1.metric(
        "Average Quality Score",
        f"{average_quality:.2f}/100"
        if pd.notna(average_quality) else "N/A"
    )

    q2.metric(
        "Average Response Time",
        f"{average_evaluation_time:.2f}s"
        if pd.notna(average_evaluation_time) else "N/A"
    )

    q3.metric(
        "Average Token Speed",
        f"{average_evaluation_speed:.2f} tokens/s"
        if pd.notna(average_evaluation_speed) else "N/A"
    )

    st.caption(
        f"All-record average quality score, including failed records: "
        f"{all_record_quality:.2f}/100. "
        "The main quality KPI uses successful responses only. "
        "Quality scores are heuristic estimates, not verified factual correctness."
        if pd.notna(all_record_quality)
        else "Quality scores are heuristic estimates, not verified factual correctness."
    )

    # --------------------------------------------------------
    # QUALITY STATUS DISTRIBUTION
    # --------------------------------------------------------

    st.subheader("📊 Quality Status Distribution")

    quality_status_counts = (
        evaluation_df["quality_status"]
        .value_counts()
        .rename_axis("Quality Status")
        .reset_index(name="Count")
    )

    if not quality_status_counts.empty:

        fig = px.pie(
            quality_status_counts,
            names="Quality Status",
            values="Count",
            hole=0.45,
            title="Evaluation Quality Status"
        )

        show_chart(fig)

    # --------------------------------------------------------
    # QUALITY SCORE DISTRIBUTION
    # --------------------------------------------------------

    st.divider()
    st.subheader("⭐ Quality Score Distribution")

    if not quality_data.empty:

        fig = px.histogram(
            quality_data,
            x="quality_score",
            nbins=15,
            title="Quality Score Distribution (Successful Responses)",
            labels={
                "quality_score": "Quality Score",
                "count": "Number of Responses"
            }
        )

        show_chart(fig)

    else:

        st.info("No successful responses with quality scores available.")

    # --------------------------------------------------------
    # QUALITY BY MODEL
    # --------------------------------------------------------

    st.divider()
    st.subheader("🤖 Quality Score by Model")

    if not quality_data.empty:

        quality_by_model = (
            quality_data.groupby("model")
            .agg(
                Average_Quality=("quality_score", "mean"),
                Evaluated_Tasks=("quality_score", "count")
            )
            .reset_index()
        )

        fig = px.bar(
            quality_by_model,
            x="model",
            y="Average_Quality",
            color="model",
            text_auto=".2f",
            range_y=[0, 105],
            title="Average Heuristic Quality Score by Model",
            labels={
                "model": "AI Model",
                "Average_Quality": "Quality Score"
            }
        )

        show_chart(fig)

        st.dataframe(
            quality_by_model.round(2),
            width="stretch",
            hide_index=True
        )

    # --------------------------------------------------------
    # QUALITY BY CATEGORY
    # --------------------------------------------------------

    st.divider()
    st.subheader("📚 Quality Score by Category")

    if not quality_data.empty:

        quality_by_category = (
            quality_data.groupby("category")
            .agg(
                Average_Quality=("quality_score", "mean"),
                Evaluated_Tasks=("quality_score", "count")
            )
            .reset_index()
        )

        fig = px.bar(
            quality_by_category,
            x="category",
            y="Average_Quality",
            color="category",
            text_auto=".2f",
            range_y=[0, 105],
            title="Average Quality Score by Task Category",
            labels={
                "category": "Task Category",
                "Average_Quality": "Quality Score"
            }
        )

        show_chart(fig)

        st.dataframe(
            quality_by_category.round(2),
            width="stretch",
            hide_index=True
        )

    # --------------------------------------------------------
    # DETAILED EVALUATION RESULTS
    # --------------------------------------------------------

    st.divider()
    st.subheader("📋 Detailed Evaluation Results")

    st.dataframe(
        evaluation_df,
        width="stretch",
        hide_index=True
    )

    csv_download(
        evaluation_df,
        "evaluation_results.csv",
        "⬇️ Download Evaluation Results CSV"
    )


# ============================================================
# SECTION 3: INTELLIGENT MODEL ROUTING
# ============================================================

st.divider()
st.header("🧠 Intelligent Model Routing")

st.caption(
    "Analysis of automatic model selection based on task category "
    "and difficulty."
)

if routing_df.empty:

    st.warning(
        "No routing results found. "
        "Run the routing benchmark to generate routing_benchmark_results.json."
    )

else:

    required_columns = {
        "task_id",
        "selected_model",
        "expected_model",
        "routing_match",
        "response_time",
        "status"
    }

    missing_columns = required_columns - set(routing_df.columns)

    if missing_columns:

        st.error(
            f"Routing results are missing required columns: "
            f"{', '.join(sorted(missing_columns))}"
        )

    else:

        routing_df = routing_df.copy()

        # Add optional columns if missing.
        if "category" not in routing_df.columns:
            routing_df["category"] = "Unknown"

        if "difficulty" not in routing_df.columns:
            routing_df["difficulty"] = "Unknown"

        for column in [
            "category",
            "difficulty",
            "selected_model",
            "expected_model"
        ]:

            routing_df[column] = (
                routing_df[column]
                .fillna("Unknown")
                .astype(str)
            )

        routing_df["status"] = normalize_status(
            routing_df["status"]
        )

        numeric_column(routing_df, "response_time")

        routing_df["routing_match"] = (
            routing_df["routing_match"]
            .apply(normalize_boolean)
        )

        # ----------------------------------------------------
        # ROUTING ACCURACY
        # ----------------------------------------------------

        total_routes = len(routing_df)

        labeled_routes = routing_df[
            routing_df["expected_model"].ne("Unknown")
        ].copy()

        labeled_total = len(labeled_routes)

        correct_routes = int(
            labeled_routes["routing_match"].sum()
        )

        incorrect_routes = labeled_total - correct_routes

        routing_accuracy = (
            correct_routes / labeled_total * 100
            if labeled_total else 0
        )

        # Request success is different from routing accuracy.
        successful_routes = int(
            (routing_df["status"] == "success").sum()
        )

        incomplete_routes = int(
            (routing_df["status"] == "incomplete").sum()
        )

        failed_routes = int(
            (routing_df["status"] == "failed").sum()
        )

        routing_success_rate = (
            successful_routes / total_routes * 100
            if total_routes else 0
        )

        # ----------------------------------------------------
        # RESPONSE TIME ANOMALY HANDLING
        # ----------------------------------------------------

        valid_routing_times = routing_df[
            "response_time"
        ].dropna()

        median_routing_time = (
            valid_routing_times.median()
            if not valid_routing_times.empty
            else float("nan")
        )

        outlier_threshold = (
            median_routing_time * 10
            if pd.notna(median_routing_time)
            and median_routing_time > 0
            else float("inf")
        )

        timing_outliers = routing_df[
            routing_df["response_time"] > outlier_threshold
        ].copy()

        normal_timing = routing_df[
            routing_df["response_time"].notna()
            & (routing_df["response_time"] <= outlier_threshold)
        ]

        average_routing_time = normal_timing[
            "response_time"
        ].mean()

        # ----------------------------------------------------
        # ROUTING KPI CARDS
        # ----------------------------------------------------

        st.subheader("📈 Routing Performance")

        r1, r2, r3, r4, r5 = st.columns(5)

        r1.metric("Total Tasks", total_routes)

        r2.metric("Correct Routes", correct_routes)

        r3.metric(
            "Routing Accuracy",
            f"{routing_accuracy:.2f}%"
        )

        r4.metric(
            "Successful Requests",
            successful_routes
        )

        r5.metric(
            "Average Response Time*",
            f"{average_routing_time:.2f}s"
            if pd.notna(average_routing_time)
            else "N/A"
        )

        st.caption(
            "Routing accuracy measures whether the selected model matches "
            "the expected model. Request success rate measures whether "
            "generation completed successfully. These are different metrics."
        )

        st.caption(
            f"*Median response time: {median_routing_time:.2f}s. "
            f"Extreme timing records flagged: {len(timing_outliers)}. "
            "The average excludes values above 10 times the median. "
            "Original measurements remain unchanged in the detailed table and CSV."
        )

        if not timing_outliers.empty:

            with st.expander(
                "⚠️ View flagged response-time anomalies"
            ):

                st.dataframe(
                    timing_outliers[
                        [
                            "task_id",
                            "category",
                            "selected_model",
                            "response_time",
                            "status"
                        ]
                    ],
                    width="stretch",
                    hide_index=True
                )

        st.divider()

        # ----------------------------------------------------
        # ROUTING ACCURACY ANALYSIS
        # ----------------------------------------------------

        st.subheader("🎯 Routing Accuracy Analysis")

        accuracy_col1, accuracy_col2 = st.columns(2)

        with accuracy_col1:

            accuracy_data = pd.DataFrame({
                "Routing Result": [
                    "Correct Routes",
                    "Incorrect Routes"
                ],
                "Count": [
                    correct_routes,
                    incorrect_routes
                ]
            })

            if accuracy_data["Count"].sum() > 0:

                fig = px.pie(
                    accuracy_data,
                    names="Routing Result",
                    values="Count",
                    hole=0.45,
                    title="Correct vs Incorrect Model Selection"
                )

                show_chart(fig)

            else:

                st.info("No labeled routing results available.")

        with accuracy_col2:

            routing_summary = pd.DataFrame({
                "Metric": [
                    "Total Tasks",
                    "Labeled Tasks",
                    "Correct Routes",
                    "Incorrect Routes",
                    "Routing Accuracy",
                    "Successful Requests",
                    "Incomplete Requests",
                    "Failed Requests",
                    "Request Success Rate"
                ],
                "Value": [
                    str(total_routes),
                    str(labeled_total),
                    str(correct_routes),
                    str(incorrect_routes),
                    f"{routing_accuracy:.2f}%",
                    str(successful_routes),
                    str(incomplete_routes),
                    str(failed_routes),
                    f"{routing_success_rate:.2f}%"
                ]
            })

            st.dataframe(
                routing_summary,
                width="stretch",
                hide_index=True
            )

        # ----------------------------------------------------
        # TASK-WISE MODEL SELECTION
        # ----------------------------------------------------

        st.divider()
        st.subheader("🔀 Task-wise Model Selection")

        display_columns = [
            "task_id",
            "category",
            "difficulty",
            "selected_model",
            "expected_model",
            "routing_match",
            "response_time",
            "status"
        ]

        available_display_columns = [
            column for column in display_columns
            if column in routing_df.columns
        ]

        st.dataframe(
            routing_df[available_display_columns],
            width="stretch",
            hide_index=True
        )

        # ----------------------------------------------------
        # RESPONSE TIME BY TASK
        # ----------------------------------------------------

        st.divider()
        st.subheader("⏱️ Response Time by Task")

        response_data = routing_df.dropna(
            subset=["response_time"]
        ).copy()

        chart_response_data = response_data[
            response_data["response_time"] <= outlier_threshold
        ]

        if not chart_response_data.empty:

            fig = px.bar(
                chart_response_data,
                x="task_id",
                y="response_time",
                color="selected_model",
                title="Response Time for Automatically Selected Models",
                labels={
                    "task_id": "Task ID",
                    "response_time": "Response Time (Seconds)",
                    "selected_model": "Selected Model"
                },
                hover_data=["category", "status"]
            )

            show_chart(fig)

        else:

            st.info("No routing response time data available.")

        # ----------------------------------------------------
        # MODEL SELECTION DISTRIBUTION
        # ----------------------------------------------------

        st.divider()
        st.subheader("📊 Model Selection Distribution")

        model_counts = (
            routing_df["selected_model"]
            .value_counts()
            .rename_axis("Model")
            .reset_index(name="Tasks")
        )

        model_col1, model_col2 = st.columns(2)

        with model_col1:

            fig = px.pie(
                model_counts,
                names="Model",
                values="Tasks",
                hole=0.45,
                title="Distribution of Selected Models"
            )

            show_chart(fig)

        with model_col2:

            fig = px.bar(
                model_counts,
                x="Model",
                y="Tasks",
                color="Model",
                text_auto=True,
                title="Tasks Assigned to Each Model"
            )

            show_chart(fig)

        # ----------------------------------------------------
        # CATEGORY-WISE ROUTING
        # ----------------------------------------------------

        st.divider()
        st.subheader("📚 Routing Performance by Category")

        category_routing = (
    routing_df[
        routing_df["response_time"].notna()
        & (routing_df["response_time"] <= outlier_threshold)
    ]
    .groupby("category")
    .agg(
        Total_Tasks=("task_id", "count"),
        Average_Time=("response_time", "mean")
    )
    .reset_index()
)

        category_accuracy = (
            labeled_routes.groupby("category")
            .agg(
                Labeled_Tasks=("routing_match", "count"),
                Correct_Routes=("routing_match", "sum")
            )
            .reset_index()
        )

        category_routing = category_routing.merge(
            category_accuracy,
            on="category",
            how="left"
        )

        category_routing[
            ["Labeled_Tasks", "Correct_Routes"]
        ] = category_routing[
            ["Labeled_Tasks", "Correct_Routes"]
        ].fillna(0)

        category_routing["Accuracy"] = (
            category_routing["Correct_Routes"]
            / category_routing["Labeled_Tasks"].replace(0, pd.NA)
            * 100
        )

        st.dataframe(
            category_routing.round(2),
            width="stretch",
            hide_index=True
        )

        chart_data = category_routing.dropna(
            subset=["Accuracy"]
        )

        if not chart_data.empty:

            fig = px.bar(
                chart_data,
                x="category",
                y="Accuracy",
                color="category",
                text_auto=".2f",
                range_y=[0, 105],
                title="Routing Accuracy by Task Category",
                labels={
                    "category": "Task Category",
                    "Accuracy": "Routing Accuracy (%)"
                }
            )

            show_chart(fig)

        # ----------------------------------------------------
        # DOWNLOAD ROUTING RESULTS
        # ----------------------------------------------------

        st.divider()

        csv_download(
            routing_df,
            "routing_benchmark_results.csv",
            "⬇️ Download Routing Results CSV"
        )

        st.info(
            f"Routing evaluation: {correct_routes}/{labeled_total} "
            f"correct selections ({routing_accuracy:.2f}% accuracy)."
        )


# ============================================================
# FOOTER
# ============================================================

st.divider()

st.caption(
    "LLM Model Evaluation and Intelligent Routing System | "
    "Powered by Python, Ollama, Streamlit, Pandas and Plotly"
)