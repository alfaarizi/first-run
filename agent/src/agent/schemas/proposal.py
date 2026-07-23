"""Action proposal shape and the registry rule that gates it."""

from collections.abc import Sequence

from pydantic import BaseModel, Field


class UnregisteredActionError(ValueError):
    """Raised when a proposal names an action outside the app's registry."""


class ActionProposal(BaseModel):
    """A registered action proposed for the end user to confirm.

    A proposal executes nothing. Execution needs the end user's recorded
    confirmation, and the server revalidates the name and scope regardless
    of what any model or client sent.
    """

    action_name: str
    arguments: dict[str, str] = Field(default_factory=dict)


def propose(
    *,
    action_name: str,
    arguments: dict[str, str],
    registered_action_names: Sequence[str],
) -> ActionProposal:
    """Build the proposal, refusing any name outside the registry.

    An empty registry refuses everything: with no registry in the context,
    no proposal renders.
    """
    if action_name not in registered_action_names:
        raise UnregisteredActionError(
            f"action {action_name!r} is not in the app's registry"
        )
    return ActionProposal(action_name=action_name, arguments=dict(arguments))
