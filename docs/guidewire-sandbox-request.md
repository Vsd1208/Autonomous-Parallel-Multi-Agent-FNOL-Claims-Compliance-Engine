# Asking for Guidewire sandbox access

Nothing in this project has run against a real tenant. Only Guidewire or a licensed customer can change that. This is the
request to send, and what to do the day access arrives.

## The request

> **Subject:** Sandbox access for a DEVSummit entry: an AI extension built on ClaimCenter's event model
>
> Hello,
>
> I am a university student entering the Guidewire DEVSummit with an AI assistant for first notice of loss. It works as an
> extension of ClaimCenter rather than beside it: it reacts to a submitted claim, analyses the report and photos, and writes
> its recommendations back onto the claim. It never posts a reserve or decides a claim; only an adjuster's decision does.
>
> So far I have built against a stand-in modelled on your public Cloud API and App Events documentation, so none of it is
> verified on the real product. I keep a list of what I assumed and where I expect it to differ:
> [link to docs/guidewire-integration.md in the repository].
>
> Would it be possible to get time-limited access to a non-production ClaimCenter sandbox, or to any program for students or
> DEVSummit entrants that provides one? I would need Cloud API access to create and submit draft claims and add activities and
> notes, and an App Events webhook subscription if possible. I would use synthetic data only and share what I find.
>
> If that is not possible, a short review of the integration document by someone who knows ClaimCenter would also help a lot.
>
> Thank you,
> [your name, university, repository link]

Send it to your DEVSummit contact. Fill in the link and your details first.

## The day access arrives

Non-production sandbox and synthetic data only.

1. Run `tools/guidewire-check/check_sandbox.py` read-only. It checks the token request, the Claim API root and the response
   format, and prints what differs. Its header says which credentials to set.
2. With `--write --policy … --loss-date …` it creates and submits one draft claim, and tries an activity and a note. A
   submitted claim cannot be undone, only closed.
3. Correct `GuidewireCloudClaimCenterClient` (profile `guidewire-cloud-claims`) and `docs/guidewire-integration.md` wherever
   the tenant differs. Change each row's status to what you actually saw, and date it.
4. Then settle the open items in that document, in order: reserve authority, incidents and claimants for exposures, routing,
   and the real App Events format and authentication.
