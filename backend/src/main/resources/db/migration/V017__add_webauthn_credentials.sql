CREATE TABLE public.user_entities (
    id varchar(1000) NOT NULL,
    name varchar(100) NOT NULL,
    display_name varchar(200),
    CONSTRAINT pk_webauthn_user_entity PRIMARY KEY (id),
    CONSTRAINT uq_webauthn_user_entity__name UNIQUE (name)
);

CREATE TABLE public.user_credentials (
    credential_id varchar(1000) NOT NULL,
    user_entity_user_id varchar(1000) NOT NULL,
    public_key bytea NOT NULL,
    signature_count bigint,
    uv_initialized boolean,
    backup_eligible boolean NOT NULL,
    authenticator_transports varchar(1000),
    public_key_credential_type varchar(100),
    backup_state boolean NOT NULL,
    attestation_object bytea,
    attestation_client_data_json bytea,
    created timestamp,
    last_used timestamp,
    label varchar(1000) NOT NULL,
    CONSTRAINT pk_webauthn_user_credential PRIMARY KEY (credential_id),
    CONSTRAINT fk_webauthn_user_credential__entity FOREIGN KEY (user_entity_user_id)
        REFERENCES public.user_entities (id)
);

REVOKE ALL ON public.user_entities, public.user_credentials FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.user_entities, public.user_credentials TO app_runtime;
