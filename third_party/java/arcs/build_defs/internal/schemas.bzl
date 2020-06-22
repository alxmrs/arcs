"""Rules for generating code from Arcs schemas.

Rules are re-exported in build_defs.bzl -- use those instead.
"""

load("//devtools/build_cleaner/skylark:build_defs.bzl", "register_extension_info")
load("//third_party/java/arcs/build_defs:sigh.bzl", "sigh_command")
load("//third_party/java/arcs/build_defs/internal:util.bzl", "manifest_only", "replace_arcs_suffix")
load(":kotlin.bzl", "ARCS_SDK_DEPS", "arcs_kt_library", "arcs_kt_plan")
load(":manifest.bzl", "arcs_manifest")

def _run_schema2wasm(
        name,
        src,
        deps,
        out,
        language_name,
        language_flag,
        wasm,
        test_harness = False):
    """Generates source code for the given .arcs schema file.

    Runs sigh schema2wasm to generate the output.
    """

    if not src.endswith(".arcs"):
        fail("src must be a .arcs file")

    if type(deps) == str:
        fail("deps must be a list")

    sigh_command(
        name = name,
        srcs = [src],
        outs = [out],
        deps = deps,
        progress_message = "Generating {} entity schemas".format(language_name),

        # TODO: generated header guard should contain whole workspace-relative
        # path to file.
        sigh_cmd = "schema2wasm " +
                   language_flag + " " +
                   ("--wasm " if wasm else "") +
                   ("--test_harness " if test_harness else "") +
                   "--outdir $(dirname {OUT}) " +
                   "--outfile $(basename {OUT}) " +
                   "{SRC}",
    )

def arcs_cc_schema(name, src, deps = [], out = None):
    """Generates a C++ header file for the given .arcs schema file."""
    _run_schema2wasm(
        name = name + "_genrule",
        src = src,
        deps = deps,
        out = out or replace_arcs_suffix(src, ".h"),
        language_flag = "--cpp",
        language_name = "C++",
        wasm = False,
    )

def arcs_kt_schema(
        name,
        srcs,
        data = [],
        deps = [],
        platforms = ["jvm"],
        test_harness = True,
        testonly=False,
        visibility = None):
    """Generates a Kotlin schemas, entities, specs, handle holders, and base particles for input .arcs manifest files.

    Example:

      Direct dependency on this target is required for use.

      ```
          arcs_kt_schema(
            name = "foo_schemas",
            srcs = ["foo.arcs"],
          )

          arcs_kt_library(
            name = "arcs_lib",
            srcs = glob("*.kt"),
            deps = [":foo_schemas"],
          )
      ```

    Args:
      name: name of the target to create
      srcs: list of Arcs manifest files to include
      data: list of Arcs manifests needed at runtime
      deps: list of imported manifests
      platforms: list of target platforms (currently, `jvm` and `wasm` supported).
      test_harness: whether to generate a test harness target
      testonly: If True, only testonly targets (such as tests) can depend on this target.
      visibility: visibility of the generated arcs_kt_library

    Returns:
      Dictionary of:
        "outs": output files. other rules can use this to bundle outputs.
        "deps": deps of those outputs.
    """
    supported = ["jvm", "wasm"]

    # TODO(#5018)
    if "jvm" not in platforms:
        platforms.append("jvm")

    outs = []
    outdeps = []
    for src in srcs:
        for ext in platforms:
            if ext not in supported:
                fail("Platform %s not allowed; only %s supported.".format(ext, supported.join(",")))
            genrule_name = replace_arcs_suffix(src, "_genrule_" + ext)
            outs.append(genrule_name)
            schema2pkg(
                name = genrule_name,
                srcs = [src],
                platform = ext,
                data = data,
                testonly=testonly,
            )

    arcs_kt_library(
        name = name,
        srcs = [":" + out for out in outs],
        platforms = platforms,
        deps = ARCS_SDK_DEPS + deps,
        visibility = visibility,
        testonly=testonly
    )
    outdeps = outdeps + ARCS_SDK_DEPS

    if test_harness:
        test_harness_outs = []
        for src in srcs:
            out = replace_arcs_suffix(src, "_genrule_test_harness")
            test_harness_outs.append(out)

            schema2pkg(
                name = out,
                srcs = [src],
                data = data,
                test_harness = True,
            )

        arcs_kt_library(
            name = name + "_test_harness",
            testonly = 1,
            srcs = [":" + out for out in test_harness_outs],
            deps = ARCS_SDK_DEPS + [
                ":" + name,
                "//third_party/java/arcs:testing",
                "//third_party/kotlin/kotlinx_coroutines",
            ],
        )
    return {"outs": outs, "deps": outdeps}

def _arcs_ts_preproc_impl(ctx):
    outputs = [
        ctx.actions.declare_file(x.basename, sibling = x)
        for x in ctx.files.srcs
    ]

    cmd_args = zip([src.path for src in ctx.files.srcs], [dst.path for dst in outputs])

    ctx.actions.run_shell(
        inputs = ctx.files.srcs,
        outputs = outputs,
        command = "\n".join(["sed -e 's/-web.js/-node.js/g' {0} > {1}".format(src, dst) for src, dst in cmd_args]),
    )

    return DefaultInfo(files = depset(outputs))

arcs_ts_preprocessing = rule(
    implementation = _arcs_ts_preproc_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = [".ts", ".d.ts"]),
    },
    doc = """Processes Arcs TypeScript sources to refer to node variants of the dependencies in the platform/ directory.""",
)

def _schema2pkg_impl(ctx):
    output_name = ctx.label.name + "_GeneratedSchemas.%s.kt" % ctx.attr.platform
    out = ctx.actions.declare_file(output_name)

    args = ctx.actions.args()

    if ctx.attr.platform == "cpp":
        args.add("--cpp")
        if ctx.attr.platform != "wasm":
            fail("'wasm' platform is only supported with 'kotlin' language.")
        if ctx.attr.test_harness:
            fail("'test_harness' is only supported with 'kotlin' language.")
    else:
        args.add("--kotlin")

    if ctx.attr.platform == "wasm":
        args.add("--wasm")

    if ctx.attr.test_harness:
        args.add("--test_harness")

    args.add_all("--outdir", [out.dirname])
    args.add_all("--outfile", [output_name])
    args.add_all([src.path for src in ctx.files.srcs])

    ctx.actions.run(
        inputs = ctx.files.srcs,
        outputs = [out],
        arguments = [args],
        tools = ctx.files.data,
        executable = ctx.executable._compiler,
    )

    return [DefaultInfo(files = depset([out]))]

schema2pkg = rule(
    implementation = _schema2pkg_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = [".arcs"]),
        "deps": attr.label_list(),
        "data": attr.label_list(allow_files = True),
        "platform": attr.string(
            values = ["jvm", "wasm"],
            default = "jvm",
        ),
        "lang": attr.string(
            values = ["kotlin", "cpp"],
            default = "kotlin",
        ),
        "test_harness": attr.bool(
            default = False,
            doc = """whether to output a particle test harness only (requires lang=kotlin)""",
        ),
        "_compiler": attr.label(
            cfg = "host",
            default = Label("//src/tools:schema2pkg"),
            allow_files = True,
            executable = True,
        ),
    },
    doc = """Stand-alone schema2* tool.""",
)

def arcs_kt_gen(
        name,
        srcs,
        data = [],
        deps = [],
        platforms = ["jvm"],
        test_harness = True,
        testonly=False,
        visibility = None):
    """Generates Kotlin files for the given .arcs files.

    TODO(alxr): Move to manifest.bzl file

    This is a convenience wrapper that combines all code generation targets based on arcs files.

    Args:
      name: name of the target to create
      srcs: list of Arcs manifest files to include
      data: list of Arcs manifests needed at runtime
      deps: list of dependent arcs targets, such as an arcs_kt_gen target in a different package
      platforms: list of target platforms (currently, `jvm` and `wasm` supported).
      test_harness: whether to generate a test harness target
      testonly: If True, only testonly targets (such as tests) can depend on this target.
      visibility: visibility of the generated arcs_kt_library
    """

    manifest_name = name + "_manifest"
    schema_name = name + "_schema"
    plan_name = name + "_plan"

    arcs_manifest(
        name = manifest_name,
        srcs = srcs,
        deps = manifest_only(deps) + data,
        testonly = testonly,
    )

    schema = arcs_kt_schema(
        name = schema_name,
        srcs = srcs,
        data = [":" + manifest_name],
        deps = deps,
        platforms = platforms,
        test_harness = test_harness,
        testonly=testonly,
        visibility = visibility,
    )
    plan = arcs_kt_plan(
        name = plan_name,
        srcs = srcs,
        data = [":" + manifest_name],
        deps = deps + [":" + schema_name],
        testonly=testonly,
        platforms = platforms,
        visibility = visibility,
    )

    # generates combined library. This allows developers to more easily see what is generated.
    arcs_kt_library(
        name = name,
        srcs = depset(schema["outs"] + plan["outs"]).to_list(),
        platforms = platforms,
        deps = depset(schema["deps"] + plan["deps"]).to_list(),
        testonly=testonly,
        visibility = visibility,
    )

register_extension_info(
    extension = arcs_kt_gen,
    label_regex_for_dep = "{extension_name}\\-kt(_DO_NOT_DEPEND_JVM)?",
)
