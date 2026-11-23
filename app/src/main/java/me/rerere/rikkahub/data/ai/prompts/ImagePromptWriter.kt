package me.rerere.rikkahub.data.ai.prompts

/**
 * 把用户的出图需求改写成专业 diffusion prompt 的提示词。
 *
 * 占位符：`{content}` 用户原文，`{size}` 画布尺寸，`{locale}` 用户语言。
 */
internal val DEFAULT_IMAGE_PROMPT_WRITER_PROMPT = """
    You rewrite a user's image request into one high-quality prompt for a text-to-image diffusion model.

    The request is inside the <content> block below, the requested canvas is {size}, and the user's language is {locale}.

    Rules:
    1. Keep every concrete element of the request: subject, quantity, colors, composition, props and background details, and any text that must appear in the image (keep it verbatim).
    2. Only add details that do not contradict the request: composition, camera angle and lens, lighting, materials, art style, color palette, mood.
    3. Write the prompt in English. Text that must be rendered inside the image keeps its original language and stays quoted.
    4. Never add watermarks, signatures, logos or frames the user did not ask for.
    5. If the request is an edit of an existing image, state explicitly what must be preserved and what must change.
    6. Order the prompt as: subject and action, then setting, then style and light.
    7. Stay under 400 words and do not explain your choices.

    Reply with strict JSON only, no markdown code fences and no commentary:
    {"prompt": "<the rewritten prompt>", "negative": "<things to avoid>"}

    <content>
    {content}
    </content>
""".trimIndent()

/** 服务端没给 negative 时使用的默认避开项。 */
internal const val DEFAULT_IMAGE_NEGATIVE =
    "blurry, low quality, lowres, watermark, text artifacts, extra fingers, deformed hands, jpeg artifacts"
