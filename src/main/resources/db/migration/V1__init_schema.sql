CREATE TABLE accounts (
                          vpa           VARCHAR(255) PRIMARY KEY,
                          holder_name   VARCHAR(255) NOT NULL,
                          balance       NUMERIC(19,2) NOT NULL,
                          version       BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE transactions (
                              id              BIGSERIAL PRIMARY KEY,
                              packet_hash     VARCHAR(64) NOT NULL UNIQUE,
                              sender_vpa      VARCHAR(255) NOT NULL,
                              receiver_vpa    VARCHAR(255) NOT NULL,
                              amount          NUMERIC(19,2) NOT NULL,
                              signed_at       TIMESTAMP NOT NULL,
                              settled_at      TIMESTAMP NOT NULL,
                              bridge_node_id  VARCHAR(255) NOT NULL,
                              hop_count       INT NOT NULL,
                              status          VARCHAR(20) NOT NULL
);

CREATE UNIQUE INDEX idx_packet_hash ON transactions(packet_hash);

-- Seed a couple of demo accounts so the app has something to show immediately
INSERT INTO accounts (vpa, holder_name, balance, version) VALUES
                                                              ('alice@demo', 'Alice', 5000.00, 0),
                                                              ('bob@demo', 'Bob', 3000.00, 0),
                                                              ('carol@demo', 'Carol', 1000.00, 0);