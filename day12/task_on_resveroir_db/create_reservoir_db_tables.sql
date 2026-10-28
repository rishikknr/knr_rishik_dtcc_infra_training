
PRAGMA foreign_keys = ON;

CREATE TABLE reservoirs (
    id INTEGER PRIMARY KEY,
    name TEXT,
    location TEXT,
    capacity REAL
);

CREATE TABLE weather (
    id INTEGER PRIMARY KEY,
    reservoir_id INTEGER,
    rainfall REAL,
    temperature REAL,
    record_date TEXT,
    FOREIGN KEY (reservoir_id) REFERENCES reservoirs(id)
);

CREATE TABLE inflow (
    id INTEGER PRIMARY KEY,
    reservoir_id INTEGER,
    record_date TEXT,
    inflow_value REAL,
    FOREIGN KEY (reservoir_id) REFERENCES reservoirs(id)
);

CREATE TABLE predictions (
    id INTEGER PRIMARY KEY,
    reservoir_id INTEGER,
    predicted_inflow REAL,
    prediction_date TEXT,
    FOREIGN KEY (reservoir_id) REFERENCES reservoirs(id)
);
