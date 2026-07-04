package eu.monniot.feed.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

// Matches the Json config used by the Ktor HTTP client (ignoreUnknownKeys = true).
private val json = Json { ignoreUnknownKeys = true }

/**
 * #24 contract tests: the client [Category] and [CategoryCreateResponse]
 * models must decode the shapes emitted by `GET /v1/categories` and
 * `POST /v1/categories` (server/src/db.rs Category struct;
 * server/src/api/types.rs CreateCategoryResponse).
 */
class CategoryModelTest {

    @Test
    fun decodes_categories_list_response() {
        val payload = """
            {
              "data": [
                { "id": 1, "name": "Tech", "position": 0 },
                { "id": 2, "name": "News", "position": 1 },
                { "id": 3, "name": "Personal Blogs", "position": 2 }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<List<Category>>>(payload)
        assertEquals(3, response.data.size)
        assertEquals("Tech", response.data[0].name)
        assertEquals(0, response.data[0].position)
        assertEquals("Personal Blogs", response.data[2].name)
        assertEquals(2, response.data[2].position)
    }

    @Test
    fun decodes_empty_categories_list() {
        val payload = """{ "data": [] }"""
        val response = json.decodeFromString<ApiResponse<List<Category>>>(payload)
        assertEquals(0, response.data.size)
    }

    @Test
    fun decodes_category_create_response() {
        val payload = """
            {
              "data": {
                "id": 4,
                "message": "Category 'Tech' created successfully"
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<CategoryCreateResponse>>(payload)
        assertEquals(4, response.data.id)
        assertEquals("Category 'Tech' created successfully", response.data.message)
    }
}
