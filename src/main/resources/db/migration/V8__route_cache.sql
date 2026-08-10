CREATE TABLE route_cache (
    route_cache_id    BIGINT       GENERATED ALWAYS AS IDENTITY,
    from_key          VARCHAR(150) NOT NULL,
    to_key            VARCHAR(150) NOT NULL,
    mode              VARCHAR(10)  NOT NULL,
    response_payload  JSONB        NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expired_at        TIMESTAMP    NOT NULL,

    CONSTRAINT pk_route_cache       PRIMARY KEY (route_cache_id),
    CONSTRAINT uk_route_cache_key   UNIQUE (from_key, to_key, mode),
    CONSTRAINT chk_route_cache_mode CHECK (mode IN ('driving', 'foot', 'transit'))
);

CREATE INDEX idx_route_cache_expired_at ON route_cache (expired_at);

CREATE TABLE route_transit_lane_cache (
    lane_cache_id     BIGINT       GENERATED ALWAYS AS IDENTITY,
    map_obj           VARCHAR(255) NOT NULL,
    response_payload  JSONB        NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expired_at        TIMESTAMP    NOT NULL,

    CONSTRAINT pk_route_transit_lane_cache         PRIMARY KEY (lane_cache_id),
    CONSTRAINT uk_route_transit_lane_cache_map_obj UNIQUE (map_obj)
);

CREATE INDEX idx_route_transit_lane_cache_expired_at ON route_transit_lane_cache (expired_at);
