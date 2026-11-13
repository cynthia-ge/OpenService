"""OpenService-owned defaults; these are not a full vendor data dictionary."""
COMMON = {"short_description": "Summary", "description": "Description", "assigned_to": "Assigned to", "assignment_group": "Assignment group"}
TABLES = {
    "incident": {"label": "Incidents", "prefix": "INC", "fields": {**COMMON, "state": "State", "priority": "Priority", "caller_id": "Caller"}, "states": {"1": "New", "2": "In progress", "3": "On hold", "6": "Resolved", "7": "Closed"}, "flow": {"1": ["2"], "2": ["3", "6"], "3": ["2", "6"], "6": ["2", "7"], "7": []}},
    "problem": {"label": "Problems", "prefix": "PRB", "fields": {**COMMON, "state": "State", "priority": "Priority"}, "states": {"new": "New", "assess": "Assess", "fix": "Fix in progress", "closed": "Closed"}, "flow": {"new": ["assess"], "assess": ["fix"], "fix": ["closed"], "closed": []}},
    "change_request": {"label": "Changes", "prefix": "CHG", "fields": {**COMMON, "state": "State", "priority": "Priority", "risk": "Risk", "implementation_plan": "Implementation plan"}, "states": {"draft": "Draft", "review": "Awaiting approval", "approved": "Approved", "implement": "Implementing", "closed": "Closed", "rejected": "Rejected"}, "flow": {"draft": ["review"], "review": ["approved", "rejected"], "approved": ["implement"], "implement": ["closed"], "rejected": ["draft"], "closed": []}},
    "sc_request": {"label": "Requests", "prefix": "REQ", "fields": {**COMMON, "state": "State", "priority": "Priority", "requested_for": "Requested for"}, "states": {"open": "Open", "fulfilling": "Fulfilling", "complete": "Complete"}, "flow": {"open": ["fulfilling"], "fulfilling": ["complete"], "complete": []}},
    "cmdb_ci": {"label": "Configuration items", "prefix": "CI", "fields": {**COMMON, "name": "Name", "serial_number": "Serial number", "location": "Location", "owned_by": "Owned by"}, "states": {}, "flow": {}},
    "kb_knowledge": {"label": "Knowledge", "prefix": "KB", "fields": {**COMMON, "text": "Article", "category": "Category"}, "states": {}, "flow": {}},
    "sys_user_group": {"label": "Groups", "prefix": "GRP", "fields": {"short_description": "Group name", "description": "Description", "manager": "Manager"}, "states": {}, "flow": {}},
}
SYSTEM = {"sys_id", "number", "sys_created_on", "sys_updated_on", "sys_created_by", "sys_updated_by", "sys_mod_count", "sys_class_name"}
