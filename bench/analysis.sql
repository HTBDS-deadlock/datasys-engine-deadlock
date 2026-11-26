-- Log analysis through the engine's own joins (exercise 12).
-- Prepare the inputs first: bash bench/make_runs.sh
CREATE TABLE logs (l_time STRING, sessionId STRING, statementNumber LONG, tid LONG,
                   level STRING, className STRING, message STRING);
COPY logs FROM 'generated/joinbench/logs.csv';

CREATE TABLE runs (r_session STRING, r_config STRING, r_repetition LONG);
COPY runs FROM 'bench/runs.csv';

-- Each join line, labelled with the configuration that produced it.
SELECT * FROM logs, runs WHERE sessionId = r_session AND className = 'HashJoinOperator';
