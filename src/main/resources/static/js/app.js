/* Minimal progressive enhancement for the analyst workbench.
   Nothing here implements business logic — all rules stay server-side. */

document.addEventListener('DOMContentLoaded', function () {
    // Prevent accidental double submission on state-changing forms and
    // expose a loading label. The request itself is still a normal POST.
    document.querySelectorAll('form').forEach(function (form) {
        form.addEventListener('submit', function (event) {
            var submitter = event.submitter;
            form.querySelectorAll('button[type="submit"]')
                .forEach(function (btn) {
                    var busy = btn.getAttribute('data-busy-label');
                    if (busy) { btn.textContent = busy; }
                    btn.disabled = true;
                    // Keep the clicked button's name/value in the POST.
                    if (btn === submitter && btn.name) {
                        var hidden = document.createElement('input');
                        hidden.type = 'hidden';
                        hidden.name = btn.name;
                        hidden.value = btn.value;
                        form.appendChild(hidden);
                    }
                });
        });
    });

    // Show the target-route control only for REROUTE / OVERRIDE.
    var routeField = document.getElementById('target-route-field');
    if (routeField) {
        var radios = document.querySelectorAll(
            'input[name="disposition"]');
        var toggle = function () {
            var checked = document.querySelector(
                'input[name="disposition"]:checked');
            var needsRoute = checked &&
                (checked.value === 'REROUTE' ||
                    checked.value === 'OVERRIDE');
            routeField.hidden = !needsRoute;
        };
        radios.forEach(function (r) {
            r.addEventListener('change', toggle);
        });
        toggle();
    }
});
