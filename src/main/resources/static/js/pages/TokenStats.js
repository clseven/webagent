// Token 用量统计页组件
const TokenStatsPage = {
    template: `
        <div class="token-stats-page page-container">
            <h1>Token 用量统计</h1>

            <!-- 时间范围选择 -->
            <div class="time-range-selector">
                <button
                    v-for="range in timeRanges"
                    :key="range.days"
                    :class="{ active: selectedDays === range.days }"
                    @click="selectRange(range.days)"
                >
                    {{ range.label }}
                </button>
            </div>

            <!-- 统计卡片 -->
            <div class="stats-cards">
                <div class="stat-card">
                    <div class="stat-number">{{ formatNumber(summary.totalTokens) }}</div>
                    <div class="stat-label">总 Token</div>
                </div>
                <div class="stat-card">
                    <div class="stat-number">{{ formatNumber(summary.promptTokens) }}</div>
                    <div class="stat-label">输入 Token</div>
                    <div class="stat-percent" v-if="summary.totalTokens > 0">
                        {{ Math.round(summary.promptTokens * 100 / summary.totalTokens) }}%
                    </div>
                </div>
                <div class="stat-card">
                    <div class="stat-number">{{ formatNumber(summary.completionTokens) }}</div>
                    <div class="stat-label">输出 Token</div>
                    <div class="stat-percent" v-if="summary.totalTokens > 0">
                        {{ Math.round(summary.completionTokens * 100 / summary.totalTokens) }}%
                    </div>
                </div>
                <div class="stat-card">
                    <div class="stat-number">{{ summary.cacheHitRate }}%</div>
                    <div class="stat-label">缓存命中率</div>
                </div>
            </div>

            <!-- 图表区域 -->
            <div class="charts-row">
                <!-- 每日趋势折线图 -->
                <div class="chart-card">
                    <h3>每日用量趋势</h3>
                    <div class="chart-container">
                        <canvas ref="lineChart"></canvas>
                    </div>
                </div>

                <!-- 模型分布饼图 -->
                <div class="chart-card">
                    <h3>模型用量分布</h3>
                    <div class="chart-container">
                        <canvas ref="pieChart"></canvas>
                    </div>
                </div>
            </div>
        </div>
    `,
    setup() {
        const store = Vue.inject('store');

        const selectedDays = Vue.ref(7);
        const summary = Vue.ref({
            totalTokens: 0,
            promptTokens: 0,
            completionTokens: 0,
            cacheHitTokens: 0,
            cacheHitRate: 0
        });
        const dailyStats = Vue.ref([]);
        const modelStats = Vue.ref([]);

        const lineChart = Vue.ref(null);
        const pieChart = Vue.ref(null);
        let lineChartInstance = null;
        let pieChartInstance = null;

        const timeRanges = [
            { label: '今天', days: 1 },
            { label: '近7天', days: 7 },
            { label: '近30天', days: 30 },
            { label: '近90天', days: 90 }
        ];

        // 加载数据
        const loadData = async () => {
            try {
                // 并行加载数据
                const [summaryData, dailyData, modelData] = await Promise.all([
                    api.getTokenSummary(selectedDays.value),
                    api.getTokenDaily(selectedDays.value),
                    api.getTokenByModel(selectedDays.value)
                ]);

                summary.value = summaryData || {};
                dailyStats.value = dailyData || [];
                modelStats.value = modelData || [];

                Vue.nextTick(() => {
                    renderLineChart();
                    renderPieChart();
                });
            } catch (e) {
                console.error('加载 Token 统计失败:', e);
            }
        };

        // 选择时间范围
        const selectRange = (days) => {
            selectedDays.value = days;
            loadData();
        };

        // 渲染折线图
        const renderLineChart = () => {
            if (!lineChart.value) return;

            if (lineChartInstance) {
                lineChartInstance.destroy();
            }

            const ctx = lineChart.value.getContext('2d');
            const labels = dailyStats.value.map(d => d.date);
            const data = dailyStats.value.map(d => d.totalTokens);

            lineChartInstance = new Chart(ctx, {
                type: 'line',
                data: {
                    labels,
                    datasets: [{
                        label: 'Token 用量',
                        data,
                        borderColor: '#4CAF50',
                        backgroundColor: 'rgba(76, 175, 80, 0.1)',
                        fill: true,
                        tension: 0.3
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: {
                            display: false
                        }
                    },
                    scales: {
                        y: {
                            beginAtZero: true,
                            ticks: {
                                callback: (value) => formatNumber(value)
                            }
                        }
                    }
                }
            });
        };

        // 渲染饼图
        const renderPieChart = () => {
            if (!pieChart.value) return;

            if (pieChartInstance) {
                pieChartInstance.destroy();
            }

            const ctx = pieChart.value.getContext('2d');
            const labels = modelStats.value.map(d => d.model);
            const data = modelStats.value.map(d => d.totalTokens);

            const colors = [
                '#4CAF50', '#2196F3', '#FF9800', '#9C27B0',
                '#F44336', '#00BCD4', '#FF5722', '#607D8B'
            ];

            pieChartInstance = new Chart(ctx, {
                type: 'doughnut',
                data: {
                    labels,
                    datasets: [{
                        data,
                        backgroundColor: colors.slice(0, data.length),
                        borderWidth: 2,
                        borderColor: '#fff'
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: {
                            position: 'bottom'
                        }
                    }
                }
            });
        };

        // 格式化数字
        const formatNumber = (num) => {
            if (!num) return '0';
            if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M';
            if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
            return num.toString();
        };

        Vue.onMounted(() => {
            loadData();
        });

        return {
            store,
            selectedDays,
            summary,
            dailyStats,
            modelStats,
            lineChart,
            pieChart,
            timeRanges,
            selectRange,
            formatNumber
        };
    }
};
