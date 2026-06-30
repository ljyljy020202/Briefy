from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import briefings, collections, health
from app.core.config import settings

app = FastAPI(title="Briefy Agent", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=[settings.backend_url],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router, prefix="/health", tags=["health"])
app.include_router(briefings.router, prefix="/briefings", tags=["briefings"])
app.include_router(collections.router, prefix="/collections", tags=["collections"])
