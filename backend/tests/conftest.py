"""Tests run against a real local Postgres database, `mpf_test`, recreated per session."""

import os
import subprocess
from dataclasses import replace

import pytest

from mpf import config, db
from mpf.repo import seed_reference_data

TEST_DB = os.environ.get("MPF_TEST_DB", "mpf_test")
DYNAMIC_TABLES = ["alert", "lead", "report", "saved_property", "saved_search", "user_post", "phone_otp",
                  "auth_token", "app_user", "review_item", "merge_decision", "price_event", "snapshot",
                  "listing", "property"]


@pytest.fixture(scope="session")
def settings():
    subprocess.run(["dropdb", "--if-exists", TEST_DB], check=True, capture_output=True)
    subprocess.run(["createdb", TEST_DB], check=True, capture_output=True)
    s = replace(config.load(), database_url=f"postgresql:///{TEST_DB}", extractor="rules")
    with db.connect(s.database_url) as conn:
        db.migrate(conn)
        with conn.transaction():
            seed_reference_data(conn)
    return s


@pytest.fixture
def conn(settings):
    with db.connect(settings.database_url) as c:
        c.execute(f"TRUNCATE {', '.join(DYNAMIC_TABLES)} CASCADE")
        c.execute("DELETE FROM source WHERE id <> 'user-posts'")
        yield c
