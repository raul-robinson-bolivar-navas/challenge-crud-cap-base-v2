package com.pragma.caplab.api;

import com.pragma.caplab.core.OpResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * CRUD de ítems.
 * El parámetro {@code node} indica a qué nodo del clúster "se conecta el cliente".
 * Cambiar de nodo (n1, n2, n3) es la forma de observar qué ve cada réplica.
 *
 * @author Raul Bolivar Navas.
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService items;

    public ItemController(ItemService items) {
        this.items = items;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OpResult<ItemView>> create(@RequestParam(defaultValue = "n1") String node,
                                           @Valid @RequestBody ItemRequest request) {
        return items.create(node, request);
    }

    @GetMapping
    public Mono<OpResult<List<ItemView>>> list(@RequestParam(defaultValue = "n1") String node) {
        return items.list(node);
    }

    @GetMapping("/{id}")
    public Mono<OpResult<ItemView>> get(@RequestParam(defaultValue = "n1") String node, @PathVariable String id) {
        return items.get(node, id);
    }

    @PutMapping("/{id}")
    public Mono<OpResult<ItemView>> update(@RequestParam(defaultValue = "n1") String node,
                                           @PathVariable String id,
                                           @Valid @RequestBody ItemRequest request) {
        return items.update(node, id, request);
    }

    @DeleteMapping("/{id}")
    public Mono<OpResult<ItemView>> delete(@RequestParam(defaultValue = "n1") String node, @PathVariable String id) {
        return items.delete(node, id);
    }
}
