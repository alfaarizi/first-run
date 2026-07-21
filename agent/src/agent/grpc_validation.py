"""Request validation shared by the gRPC servicers."""

import uuid

import grpc


async def abort_unless_uuids(
    message: object, context: grpc.aio.ServicerContext, *field_names: str
) -> None:
    """Abort the call INVALID_ARGUMENT unless every named field is a UUID."""
    for name in field_names:
        try:
            uuid.UUID(getattr(message, name))
        except ValueError:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT, f"{name} is not a UUID"
            )
