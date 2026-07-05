"""Deterministic Langfuse settings, set before any agent module imports."""

import os

os.environ.setdefault("LANGFUSE_HOST", "http://localhost:3000")
os.environ.setdefault("LANGFUSE_PUBLIC_KEY", "lf_pk_test")
os.environ.setdefault("LANGFUSE_SECRET_KEY", "lf_sk_test")
